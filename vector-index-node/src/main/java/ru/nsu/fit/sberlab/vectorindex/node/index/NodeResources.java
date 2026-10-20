package ru.nsu.fit.sberlab.vectorindex.node.index;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ru.nsu.fit.sberlab.vectorindex.core.maintenance.Backoff;
import ru.nsu.fit.sberlab.vectorindex.core.maintenance.BuildService;
import ru.nsu.fit.sberlab.vectorindex.core.maintenance.DelayedExecutor;

/**
 * Пулы узла с понятным владельцем (§7.7): полосы применения изменений, сканы, поиск
 * (фиксированный, ограниченная очередь, быстрый отказ), сборки (общий ограниченный пул и
 * явные ForkJoin для JVector) и планировщик. Никаких executor на каждый граф.
 */
final class NodeResources implements AutoCloseable {

    private static final int APPLIER_STRIPES = 4;
    private static final int APPLIER_QUEUE = 16_384;

    final StripedApplier applier;
    final Executor scanPool;
    final Executor searchPool;
    final DelayedExecutor delayed;
    final ScheduledExecutorService housekeeper; // null в тестах: периодическая сверка не нужна
    final BuildService builds;
    private final List<Runnable> shutdown;

    NodeResources(StripedApplier applier, Executor scanPool, Executor searchPool, DelayedExecutor delayed,
                  ScheduledExecutorService housekeeper, BuildService builds, List<Runnable> shutdown) {
        this.applier = Objects.requireNonNull(applier, "applier");
        this.scanPool = Objects.requireNonNull(scanPool, "scanPool");
        this.searchPool = Objects.requireNonNull(searchPool, "searchPool");
        this.delayed = Objects.requireNonNull(delayed, "delayed");
        this.housekeeper = housekeeper;
        this.builds = Objects.requireNonNull(builds, "builds");
        this.shutdown = List.copyOf(shutdown);
    }

    static NodeResources create(String name) {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        List<Runnable> shutdown = new ArrayList<>();

        StripedApplier applier = new StripedApplier(APPLIER_STRIPES, APPLIER_QUEUE);
        shutdown.add(applier::close);

        ExecutorService scans = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), factory(name + "-scan-"));
        shutdown.add(scans::shutdownNow);

        ExecutorService search = new ThreadPoolExecutor(cores, cores, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(cores * 64), factory(name + "-search-"), new ThreadPoolExecutor.AbortPolicy());
        shutdown.add(search::shutdownNow);

        ScheduledExecutorService housekeeper = Executors.newSingleThreadScheduledExecutor(factory(name + "-housekeeper-"));
        shutdown.add(housekeeper::shutdownNow);

        int buildThreads = Math.max(1, cores / 4);
        ExecutorService buildPool = new ThreadPoolExecutor(buildThreads, buildThreads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4_096), factory(name + "-build-"), new ThreadPoolExecutor.AbortPolicy());
        shutdown.add(buildPool::shutdownNow);
        ForkJoinPool simd = new ForkJoinPool(Math.max(1, cores / 2));
        shutdown.add(simd::shutdownNow);
        ForkJoinPool parallel = new ForkJoinPool(Math.max(1, cores / 2));
        shutdown.add(parallel::shutdownNow);

        DelayedExecutor delayed = DelayedExecutor.of(housekeeper);
        BuildService builds = new BuildService(buildPool, delayed,
                Backoff.exponential(Duration.ofSeconds(1), Duration.ofMinutes(1)), simd, parallel);
        shutdown.add(0, builds::close);

        return new NodeResources(applier, scans, search, delayed, housekeeper, builds, shutdown);
    }

    @Override
    public void close() {
        for (Runnable action : shutdown) {
            action.run();
        }
    }

    private static ThreadFactory factory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
