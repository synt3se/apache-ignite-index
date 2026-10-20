package ru.nsu.fit.sberlab.vectorindex.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

import ru.nsu.fit.sberlab.vectorindex.core.algorithm.SearchStructure;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.VectorAlgorithm;

/**
 * Один эпизод владения логической партицией для одного определения индекса.
 *
 * <p>Состояние — один неизменяемый {@link State}, опубликованный volatile-ссылкой.
 * Писатели ({@code upsert/delete}, {@code capture}, {@code publish}, скан, {@code close})
 * берут короткий замок только между собой и никогда не ждут поисков; поиск идёт без замков
 * по виду, удерживающему базу счётчиком ссылок.
 *
 * <p>Ревизия базы: база перекрывает записи слоёв с ревизией не больше своей. Для сборки это
 * ревизия захвата (в active остаются только более новые записи), для скана — ревизия его
 * начала: записи, применённые до старта скана, уступают прочитанным им значениям.
 *
 * <p>Скан и сборка: пока скан открыт, {@code capture} запрещён (ревизия базы монотонна)
 * и дедупликация отключена — сравнивать надо с будущей базой скана, а не с текущим видом.
 */
public final class PartitionRuntime<K> implements AutoCloseable {

    private static final long NO_PENDING_REPAIR = Long.MIN_VALUE;

    private final RuntimeConfig config;
    private final VectorSpec spec;
    private final NanoClock clock;
    private final ReentrantLock writers = new ReentrantLock();
    private volatile State state = State.initial();
    private long revision; // под writers

    private final AtomicLong repairRequested = new AtomicLong();
    private volatile long repairCompleted;
    private final AtomicLong repairPendingSince = new AtomicLong(NO_PENDING_REPAIR);

    private final LongAdder writes = new LongAdder();
    private final LongAdder dedupSkipped = new LongAdder();
    private final LongAdder buildsPublished = new LongAdder();
    private final LongAdder buildFailures = new LongAdder();
    private final LongAdder publishRejected = new LongAdder();
    private final LongAdder annUnderfilled = new LongAdder();
    private final LongAdder exactFallbacks = new LongAdder();
    private final Map<RejectReason, LongAdder> rejected = new EnumMap<>(RejectReason.class);
    private final LongAdder lockWaits = new LongAdder();
    private final LongAdder lockWaitNanos = new LongAdder();
    private final AtomicLong lockWaitMax = new AtomicLong();
    private volatile long lastBuildDurationNanos;
    private volatile long lastPublishLatencyNanos;

    private PartitionRuntime(RuntimeConfig config) {
        this.config = config;
        this.spec = config.spec();
        this.clock = config.clock();
        for (RejectReason reason : RejectReason.values()) {
            rejected.put(reason, new LongAdder());
        }
    }

    /** Новый runtime в фазе {@link Phase#LOADING}: изменения принимаются сразу, до первого скана. */
    public static <K> PartitionRuntime<K> create(RuntimeConfig config) {
        return new PartitionRuntime<>(Objects.requireNonNull(config, "config"));
    }

    public String indexId() {
        return config.indexId();
    }

    public int partition() {
        return config.partition();
    }

    public VectorSpec spec() {
        return spec;
    }

    public VectorAlgorithm algorithm() {
        return config.algorithm();
    }

    NanoClock clock() {
        return clock;
    }

    // ---------------------------------------------------------------- запись

    /**
     * Принимает текущее значение ключа. Вектор копируется (для COSINE — нормализуется) до замка.
     * Неверный вектор — {@link ApplyResult.Status#REJECTED}: ключ исключается из проекции.
     *
     * @throws IllegalArgumentException {@code vector == null} — отсутствие вектора решает адаптер
     */
    public ApplyResult upsert(K key, float[] vector) {
        Objects.requireNonNull(key, "key");
        Ingest ingest = spec.ingest(vector);
        if (ingest instanceof Ingest.Accepted accepted) {
            return write(key, accepted.vector(), null);
        }
        return write(key, null, ((Ingest.Rejected) ingest).reason());
    }

    public ApplyResult delete(K key) {
        Objects.requireNonNull(key, "key");
        return write(key, null, null);
    }

    private ApplyResult write(Object key, float[] vector, RejectReason rejectedReason) {
        ApplyResult result;
        State next;
        lockWriters();
        try {
            State s = state;
            if (s.phase == Phase.CLOSED) {
                return ApplyResult.CLOSED;
            }
            if (rejectedReason != null) {
                countRejected(rejectedReason);
            }
            float[] current = s.resolve(key);
            if (s.scan == null) {
                if (vector == null && current == null) {
                    return rejectedReason == null ? ApplyResult.UNCHANGED : ApplyResult.rejected(rejectedReason, -1);
                }
                if (vector != null && current != null && Arrays.equals(vector, current)) {
                    dedupSkipped.increment();
                    return ApplyResult.UNCHANGED;
                }
            }
            long rev = ++revision;
            long live = s.liveCount + (vector != null ? 1 : 0) - (current != null ? 1 : 0);
            WriteLog log = s.scan == null ? null : s.scanWrites.push(key);
            next = s.withActive(s.active.put(key, new Entry(rev, vector)), live, log);
            state = next;
            writes.increment();
            result = rejectedReason == null ? ApplyResult.applied(rev) : ApplyResult.rejected(rejectedReason, rev);
        } finally {
            writers.unlock();
        }
        requestOptimizationIfNeeded(next);
        return result;
    }

    // ---------------------------------------------------------------- чтение

    /** Вид текущего состояния; пусто, если runtime закрыт. Не блокирует писателей. */
    public Optional<ReadView<K>> openView() {
        while (true) {
            State s = state;
            if (s.phase == Phase.CLOSED) {
                return Optional.empty();
            }
            if (s.base.tryRetain()) {
                return Optional.of(new StateView<>(this, s));
            }
            // База уже отпущена: состояние сменилось, перечитать.
        }
    }

    // ---------------------------------------------------------------- сборка

    /**
     * O(1) под замком: {@code frozen' = frozen + [active]}, {@code active' = ∅}.
     * Пусто, если runtime не в READY, открыт скан, уже есть незавершённый токен
     * или собирать нечего.
     */
    public Optional<BuildToken<K>> capture() {
        lockWriters();
        try {
            State s = state;
            if (s.phase != Phase.READY || s.scan != null || s.outstanding != null) {
                return Optional.empty();
            }
            if (s.active.isEmpty() && s.frozen.isEmpty() && s.base.optimized()) {
                return Optional.empty();
            }
            List<DeltaLayer> frozen = s.frozen;
            if (!s.active.isEmpty()) {
                List<DeltaLayer> grown = new ArrayList<>(frozen.size() + 1);
                grown.addAll(frozen);
                grown.add(s.active);
                frozen = Collections.unmodifiableList(grown);
            }
            s.base.retain();
            BuildToken<K> token = new BuildToken<>(this, s.base, frozen, revision, clock.nanoTime());
            state = s.captured(frozen, token);
            return Optional.of(token);
        } finally {
            writers.unlock();
        }
    }

    /**
     * O(1) под замком. Принимается, только если runtime открыт, токен — текущий, а база и
     * frozen идентичны захваченным. Новое состояние {@code {built, [], active}}; старая база
     * закрывается, когда её отпустит последний вид. При отказе {@code built} закрывается.
     * Токен закрывается в любом исходе.
     */
    public PublishResult publish(BuildToken<K> token, SearchStructure built) {
        Objects.requireNonNull(built, "built");
        if (token.runtime != this) {
            built.close();
            throw new IllegalArgumentException("token belongs to another runtime");
        }
        BaseSegment fresh;
        try {
            MaterializedInput input = token.materialized();
            fresh = BaseSegment.of(input.keys, input.vectors, token.captureRevision, built);
        } catch (RuntimeException e) {
            built.close();
            token.close();
            throw e;
        }
        State previous;
        long now;
        lockWriters();
        try {
            State s = state;
            boolean current = s.phase != Phase.CLOSED && s.outstanding == token
                    && s.base == token.base && s.frozen == token.frozen;
            if (!current) {
                if (s.outstanding == token) {
                    state = s.withoutOutstanding();
                }
                previous = null;
            } else {
                state = s.published(fresh);
                previous = s;
            }
            now = clock.nanoTime();
        } finally {
            writers.unlock();
        }
        token.close();
        if (previous == null) {
            fresh.release();
            publishRejected.increment();
            return PublishResult.REJECTED_STALE;
        }
        previous.base.release();
        buildsPublished.increment();
        lastPublishLatencyNanos = now - token.capturedAtNanos;
        lastBuildDurationNanos = now - token.materializedAtNanos();
        return PublishResult.PUBLISHED;
    }

    /**
     * Сборка не удалась: данные и frozen-слои сохраняются и доступны поиску, состояние
     * BUILDING снимается (следующий {@code capture} разрешён и добавит ещё один слой).
     * Токен закрывается.
     */
    public void buildFailed(BuildToken<K> token, Throwable cause) {
        if (token.runtime != this) {
            throw new IllegalArgumentException("token belongs to another runtime");
        }
        lockWriters();
        try {
            State s = state;
            if (s.outstanding == token) {
                state = s.buildFailed(String.valueOf(cause));
            }
        } finally {
            writers.unlock();
        }
        buildFailures.increment();
        token.close();
    }

    /** Токен ещё может быть опубликован (сборку имеет смысл продолжать). */
    public boolean isCurrent(BuildToken<?> token) {
        State s = state;
        return s.phase != Phase.CLOSED && s.outstanding == token && s.base == token.base;
    }

    /** Политика просит сборку, и её можно начать сейчас. */
    public boolean needsOptimization() {
        State s = state;
        return canStartBuild(s) && config.policy().shouldOptimize(s.layerStats());
    }

    // ---------------------------------------------------------------- скан и сверка

    /**
     * Начинает скан: фиксирует ревизию старта и тикет сверки, вытесняет прежнюю сессию.
     * Для закрытого runtime возвращает уже завершённую сессию.
     */
    public ScanSession<K> beginScan() {
        lockWriters();
        try {
            State s = state;
            if (s.phase == Phase.CLOSED) {
                return new ScanSession<>(this, revision, repairRequested.get(), true);
            }
            if (s.scan != null) {
                s.scan.finish(); // вытеснена: её offer/publish/close больше ничего не меняют
            }
            ScanSession<K> session = new ScanSession<>(this, revision, repairRequested.get(), false);
            state = s.withScan(session, WriteLog.EMPTY);
            return session;
        } finally {
            writers.unlock();
        }
    }

    /**
     * Публикует базу скана {@code {scan, [], active}} с ревизией старта скана. Тяжёлая часть
     * (построение базы и подсчёт {@code liveCount} по журналу записей скана) — вне замка;
     * под замком досчитываются только записи после снимка.
     */
    public PublishResult publishScan(ScanSession<K> session) {
        if (session.runtime != this) {
            throw new IllegalArgumentException("scan session belongs to another runtime");
        }
        if (!session.finish()) {
            publishRejected.increment();
            return PublishResult.REJECTED_STALE;
        }
        BaseSegment staging = session.toStagingBase();
        State snapshot = state;
        if (snapshot.phase == Phase.CLOSED || snapshot.scan != session) {
            staging.release();
            publishRejected.increment();
            return PublishResult.REJECTED_STALE;
        }
        // liveCount = |скан| + Σ по ключам, записанным после старта скана: живо(active) − есть_в_скане.
        Set<Object> counted = new HashSet<>();
        long live = staging.size();
        for (WriteLog node = snapshot.scanWrites; node != WriteLog.EMPTY; node = node.next) {
            if (counted.add(node.key)) {
                live += contribution(snapshot.active, staging, node.key);
            }
        }
        State previous;
        State next;
        lockWriters();
        try {
            State s = state;
            if (s.phase == Phase.CLOSED || s.scan != session) {
                previous = null;
                next = null;
            } else {
                Set<Object> recent = new HashSet<>();
                for (WriteLog node = s.scanWrites; node != snapshot.scanWrites; node = node.next) {
                    if (recent.add(node.key)) {
                        long before = counted.contains(node.key) ? contribution(snapshot.active, staging, node.key) : 0;
                        live += contribution(s.active, staging, node.key) - before;
                    }
                }
                next = s.scanPublished(staging, live);
                state = next;
                previous = s;
                repairCompleted = Math.max(repairCompleted, session.repairTicket);
                if (repairRequested.get() <= repairCompleted) {
                    repairPendingSince.set(NO_PENDING_REPAIR);
                }
            }
        } finally {
            writers.unlock();
        }
        if (previous == null) {
            staging.release();
            publishRejected.increment();
            return PublishResult.REJECTED_STALE;
        }
        previous.base.release();
        requestOptimizationIfNeeded(next);
        return PublishResult.PUBLISHED;
    }

    void abortScan(ScanSession<K> session) {
        if (!session.finish()) {
            return;
        }
        State next = null;
        lockWriters();
        try {
            State s = state;
            if (s.scan == session) {
                next = s.withScan(null, null);
                state = next;
            }
        } finally {
            writers.unlock();
        }
        if (next != null) {
            requestOptimizationIfNeeded(next);
        }
    }

    /** Записи сверки: переполнение, пропуск, ошибка применения. Повторяется до завершения скана. */
    public void requestRepair(RepairReason reason) {
        Objects.requireNonNull(reason, "reason");
        repairRequested.incrementAndGet();
        repairPendingSince.compareAndSet(NO_PENDING_REPAIR, clock.nanoTime());
    }

    /** Нужна ли сверка: запросов больше, чем покрыто завершёнными сканами. */
    public boolean repairPending() {
        return repairRequested.get() > repairCompleted;
    }

    // ---------------------------------------------------------------- жизненный цикл

    /**
     * Завершает эпизод: изменения — CLOSED, публикации — REJECTED_STALE, новые виды не
     * открываются. Открытые виды и незавершённые токены продолжают удерживать свою базу.
     */
    @Override
    public void close() {
        State previous;
        lockWriters();
        try {
            previous = state;
            if (previous.phase == Phase.CLOSED) {
                return;
            }
            if (previous.scan != null) {
                previous.scan.finish();
            }
            state = previous.closed();
        } finally {
            writers.unlock();
        }
        previous.base.release();
    }

    public boolean isClosed() {
        return state.phase == Phase.CLOSED;
    }

    public RuntimeStatus status() {
        return statusOf(state);
    }

    RuntimeStatus statusOf(State s) {
        Map<RejectReason, Long> rejectedCounts = new EnumMap<>(RejectReason.class);
        rejected.forEach((reason, count) -> rejectedCounts.put(reason, count.sum()));
        long requested = repairRequested.get();
        long completed = repairCompleted;
        long since = repairPendingSince.get();
        boolean pending = requested > completed;
        long age = pending && since != NO_PENDING_REPAIR ? Math.max(0, clock.nanoTime() - since) : 0;
        return new RuntimeStatus(config.indexId(), config.partition(), s.phase, s.liveCount, s.base.size(),
                s.base.revision, s.deltaEntries(), s.deltaTombstones(), s.frozen.size(), s.optimization(),
                s.lastBuildFailure, buildsPublished.sum(), buildFailures.sum(), publishRejected.sum(),
                rejectedCounts, writes.sum(), dedupSkipped.sum(), requested, completed, pending, age,
                s.scan != null, RuntimeStatus.SourcePosition.UNKNOWN, lockWaits.sum(), lockWaitNanos.sum(),
                lockWaitMax.get(), lastBuildDurationNanos, lastPublishLatencyNanos, annUnderfilled.sum(),
                exactFallbacks.sum());
    }

    // ---------------------------------------------------------------- служебное

    void countRejected(RejectReason reason) {
        rejected.get(reason).increment();
    }

    void countUnderfilled() {
        annUnderfilled.increment();
    }

    void countExactFallback() {
        exactFallbacks.increment();
    }

    private static boolean canStartBuild(State s) {
        return s.phase == Phase.READY && s.scan == null && s.outstanding == null;
    }

    private void requestOptimizationIfNeeded(State s) {
        if (canStartBuild(s) && config.policy().shouldOptimize(s.layerStats())) {
            config.onOptimizationNeeded().accept(this);
        }
    }

    private static long contribution(DeltaLayer active, BaseSegment staging, Object key) {
        Entry entry = active.get(key); // последняя запись ключа; её ревизия новее старта скана
        long live = entry != null && entry.live() ? 1 : 0;
        long scanned = staging.ordinalOf(key) >= 0 ? 1 : 0;
        return live - scanned;
    }

    private void lockWriters() {
        if (writers.tryLock()) {
            return;
        }
        long start = clock.nanoTime();
        writers.lock();
        long waited = Math.max(0, clock.nanoTime() - start);
        lockWaits.increment();
        lockWaitNanos.add(waited);
        lockWaitMax.accumulateAndGet(waited, Math::max);
    }

    @Override
    public String toString() {
        return "PartitionRuntime[" + config.indexId() + "/" + config.partition() + ", " + state.phase + "]";
    }
}
