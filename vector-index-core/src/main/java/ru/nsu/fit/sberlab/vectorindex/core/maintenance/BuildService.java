package ru.nsu.fit.sberlab.vectorindex.core.maintenance;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.LongAdder;

import ru.nsu.fit.sberlab.vectorindex.core.BuildToken;
import ru.nsu.fit.sberlab.vectorindex.core.PartitionRuntime;
import ru.nsu.fit.sberlab.vectorindex.core.PublishResult;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.BuildContext;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.SearchStructure;

/**
 * Сборки узла: один ограниченный пул на все индексы и партиции, не больше одной сборки на
 * runtime. {@code capture} выполняется в потоке сборки — если executor отказал, захвата не
 * было и освобождать нечего; запрос повторяется с задержкой. Токен закрывается в любом
 * исходе. Ошибка сборки — повтор по {@link Backoff}; {@link Error} фиксируется и не
 * подавляется.
 *
 * <p>Пулом и планировщиком владеет вызывающий (узел): сервис их не останавливает.
 */
public final class BuildService implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(BuildService.class.getName());

    private enum Outcome { IDLE, PUBLISHED, REJECTED, FAILED }

    private final Executor pool;
    private final DelayedExecutor retries;
    private final Backoff backoff;
    private final ForkJoinPool simdPool;
    private final ForkJoinPool parallelPool;
    private final Set<PartitionRuntime<?>> queued = ConcurrentHashMap.newKeySet();
    private final Map<PartitionRuntime<?>, Integer> failuresInRow = new ConcurrentHashMap<>();
    private final LongAdder published = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder rejectedSubmissions = new LongAdder();
    private volatile boolean closed;

    public BuildService(Executor pool, DelayedExecutor retries, Backoff backoff,
                        ForkJoinPool simdPool, ForkJoinPool parallelPool) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.retries = Objects.requireNonNull(retries, "retries");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
        this.simdPool = Objects.requireNonNull(simdPool, "simdPool");
        this.parallelPool = Objects.requireNonNull(parallelPool, "parallelPool");
    }

    /** Ставит сборку в очередь; повторный запрос для уже стоящего в очереди runtime — no-op. */
    public void requestOptimization(PartitionRuntime<?> runtime) {
        if (closed || runtime.isClosed()) {
            failuresInRow.remove(runtime);
            return;
        }
        if (!queued.add(runtime)) {
            return;
        }
        try {
            pool.execute(() -> run(runtime));
        } catch (RejectedExecutionException overloaded) {
            queued.remove(runtime);
            rejectedSubmissions.increment();
            scheduleRetry(runtime);
        }
    }

    public long published() {
        return published.sum();
    }

    public long failed() {
        return failed.sum();
    }

    public long rejectedSubmissions() {
        return rejectedSubmissions.sum();
    }

    /** Дальнейшие запросы игнорируются, идущие сборки видят {@link BuildContext#cancelled()}. */
    @Override
    public void close() {
        closed = true;
    }

    private <K> void run(PartitionRuntime<K> runtime) {
        Outcome outcome = Outcome.IDLE;
        try {
            if (!closed) {
                outcome = buildOnce(runtime);
            }
        } finally {
            queued.remove(runtime);
        }
        if (outcome == Outcome.FAILED) {
            scheduleRetry(runtime);
            return;
        }
        if (outcome == Outcome.PUBLISHED) {
            failuresInRow.remove(runtime);
        }
        // Изменения, пришедшие во время сборки, могли снова превысить порог.
        if (!closed && runtime.needsOptimization()) {
            requestOptimization(runtime);
        }
    }

    private <K> Outcome buildOnce(PartitionRuntime<K> runtime) {
        Optional<BuildToken<K>> captured = runtime.capture();
        if (captured.isEmpty()) {
            return Outcome.IDLE;
        }
        try (BuildToken<K> token = captured.get()) {
            BuildContext context = BuildContext.of(simdPool, parallelPool,
                    () -> closed || !runtime.isCurrent(token));
            SearchStructure built;
            try {
                built = runtime.algorithm().build(token.input(), context);
                if (built == null) {
                    throw new IllegalStateException(runtime.algorithm().name() + " returned no structure");
                }
            } catch (CancellationException cancelled) {
                runtime.abandonBuild(token);
                return Outcome.REJECTED;
            } catch (Exception e) {
                runtime.buildFailed(token, e);
                failed.increment();
                LOG.log(System.Logger.Level.WARNING, "build failed for " + runtime, e);
                return Outcome.FAILED;
            } catch (Error e) {
                runtime.buildFailed(token, e);
                failed.increment();
                throw e;
            }
            if (runtime.publish(token, built) == PublishResult.PUBLISHED) {
                published.increment();
                return Outcome.PUBLISHED;
            }
            return Outcome.REJECTED;
        }
    }

    private void scheduleRetry(PartitionRuntime<?> runtime) {
        if (closed || runtime.isClosed()) {
            failuresInRow.remove(runtime);
            return;
        }
        int attempt = failuresInRow.merge(runtime, 1, Integer::sum);
        retries.schedule(() -> requestOptimization(runtime), backoff.delayNanos(attempt));
    }
}
