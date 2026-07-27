package ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.highload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import ru.nsu.fit.sberlab.vectorindex.common.dto.Neighbor;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchRequest;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.VectorService;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.QueryReader.QueryVector;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;

public final class BenchmarkHighLoadRunner {
    private static final Logger log = LoggerFactory.getLogger(BenchmarkHighLoadRunner.class);
    private static final long WORKER_SHUTDOWN_TIMEOUT_SECONDS = 90;
    private static final long SPIN_THRESHOLD_NANOS = 300_000L;
    private static final int DRAIN_TIMELINE_RESERVE_SECONDS = 15;

    private final VectorService service;

    public BenchmarkHighLoadRunner(VectorService service) {
        if (service == null) throw new IllegalArgumentException("service is required");
        this.service = service;
    }

    public void run(
            int maxInFlight,
            int targetRps,
            int warmupSeconds,
            int testSeconds,
            int neighborCount,
            String queriesPath,
            long preparationNanos,
            List<QueryVector> queries,
            String timelineDir
    ) {
        log.info("Starting highload benchmark: maxInFlight={}, targetRps={}, warmupSeconds={}, "
                        + "testSeconds={}, neighborCount={}, queriesPath={}",
                maxInFlight, targetRps, warmupSeconds, testSeconds, neighborCount, queriesPath);

        validateArguments(maxInFlight, targetRps, warmupSeconds, testSeconds, neighborCount);

        log.info("Loaded {} queries with vector dimension {}",
                queries.size(), queries.get(0).vector().length);

        System.out.println("=== Highload benchmark STARTED ===");
        System.out.println("Queries: " + queries.size());
        System.out.println("Vector dimension: " + queries.get(0).vector().length);
        System.out.println("Neighbor count: " + neighborCount);
        System.out.println("Target RPS: " + targetRps);
        System.out.println("Max in-flight: " + maxInFlight);
        System.out.println("Warmup: " + warmupSeconds + " s");
        System.out.println("Test duration: " + testSeconds + " s");
        System.out.println("==================================");

        ExecutorService workers = Executors.newFixedThreadPool(maxInFlight);

        try {
            if (warmupSeconds > 0) {
                System.out.println("Warmup started...");
                log.info("Warmup started");

                runPhase(workers, queries, maxInFlight, targetRps, warmupSeconds, neighborCount, false);

                log.info("Warmup finished");
                System.out.println("Warmup finished");
            }

            System.out.println("Measurement started...");
            log.info("Measurement started");

            PhaseResult result =
                    runPhase(workers, queries, maxInFlight, targetRps, testSeconds, neighborCount, true);

            printResult(result, targetRps, maxInFlight, neighborCount, preparationNanos);
            writeTimelineCsv(result, targetRps, timelineDir);
        } finally {
            log.info("Stopping benchmark executors");

            workers.shutdown();

            try {
                if (!workers.awaitTermination(WORKER_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("Worker pool did not stop in {} seconds, forcing shutdown",
                            WORKER_SHUTDOWN_TIMEOUT_SECONDS);
                    workers.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for worker pool shutdown", e);
                workers.shutdownNow();
            }
        }

        System.out.println("=== Highload benchmark FINISHED ===");
        log.info("Highload benchmark finished");
    }

    private PhaseResult runPhase(
            ExecutorService workers,
            List<QueryVector> queries,
            int maxInFlight,
            int targetRps,
            int durationSeconds,
            int neighborCount,
            boolean measured
    ) {
        log.info("Phase started: measured={}, durationSeconds={}, targetRps={}",
                measured, durationSeconds, targetRps);

        Semaphore permits = new Semaphore(maxInFlight);
        AtomicInteger querySequence = new AtomicInteger();

        long intervalNanos = Math.max(1L, 1_000_000_000L / targetRps);
        long phaseStartNanos = System.nanoTime();
        long generationEndNanos = phaseStartNanos + durationSeconds * 1_000_000_000L;

        PhaseMetrics metrics = new PhaseMetrics(phaseStartNanos, durationSeconds, targetRps);

        Thread producer = new Thread(() -> {
            long slot = 0;

            while (!Thread.currentThread().isInterrupted()) {
                long dueNanos = phaseStartNanos + slot * intervalNanos;
                if (dueNanos >= generationEndNanos) {
                    return;
                }

                long sleepNanos = dueNanos - System.nanoTime() - SPIN_THRESHOLD_NANOS;
                if (sleepNanos > 0) {
                    LockSupport.parkNanos(sleepNanos);
                }
                while (System.nanoTime() < dueNanos) {
                    Thread.onSpinWait();
                }

                long now = System.nanoTime();
                long lateSlots = (now - dueNanos) / intervalNanos;

                if (lateSlots > 0) {
                    // отстали больше чем на слот: пропускаем, но не выпускаем burst
                    metrics.skippedSlots.addAndGet(lateSlots);
                    slot += lateSlots;
                    continue;
                }

                if (measured) {
                    metrics.producerLatenessNanos.record(now - dueNanos);
                }

                emit(queries, querySequence, neighborCount, measured, permits, metrics, workers);
                slot++;
            }
        }, "bench-producer");

        producer.setDaemon(true);
        producer.setPriority(Thread.MAX_PRIORITY);
        producer.start();

        try {
            producer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            producer.interrupt();
            log.error("Highload benchmark phase interrupted", e);
            throw new RuntimeException("Highload benchmark interrupted", e);
        }

        long producerStoppedNanos = System.nanoTime();

        log.info("Request producer stopped, waiting for {} in-flight requests", metrics.inFlight.get());

        waitForRequests(metrics);

        long phaseFinishedNanos = System.nanoTime();

        log.info("Phase finished: measured={}, offered={}, skippedSlots={}, completed={}, "
                        + "successful={}, errors={}, incomplete={}, rejected={}",
                measured, metrics.scheduled.get(), metrics.skippedSlots.get(), metrics.completed.get(),
                metrics.successful.get(), metrics.errors.get(),
                metrics.incompleteResponses.get(), metrics.rejected.get());

        return new PhaseResult(metrics, phaseStartNanos, producerStoppedNanos, phaseFinishedNanos);
    }

    private void emit(
            List<QueryVector> queries,
            AtomicInteger querySequence,
            int neighborCount,
            boolean measured,
            Semaphore permits,
            PhaseMetrics metrics,
            ExecutorService workers
    ) {
        metrics.scheduled.incrementAndGet();
        metrics.recordOffered();

        if (!permits.tryAcquire()) {
            metrics.rejected.incrementAndGet();
            return;
        }

        int currentInFlight = metrics.inFlight.incrementAndGet();
        metrics.updateMaxInFlight(currentInFlight);
        metrics.recordAccepted();

        int queryIndex = Math.floorMod(querySequence.getAndIncrement(), queries.size());
        QueryVector query = queries.get(queryIndex);
        long enqueueNanos = System.nanoTime();

        try {
            workers.execute(() -> executeSearch(
                    query, neighborCount, measured, enqueueNanos, permits, metrics));
        } catch (RuntimeException e) {
            metrics.inFlight.decrementAndGet();
            metrics.submissionErrors.incrementAndGet();
            permits.release();
            log.error("Failed to submit search request to worker pool", e);
        }
    }

    private void executeSearch(
            QueryVector query,
            int neighborCount,
            boolean measured,
            long enqueueNanos,
            Semaphore permits,
            PhaseMetrics metrics
    ) {
        long startedNanos = System.nanoTime();
        boolean successfulResponse = false;
        metrics.started.incrementAndGet();

        try {
            ResponseEntity<List<Neighbor>> response = service.search(
                    new SearchRequest(query.vector(), neighborCount)
            );

            List<Neighbor> neighbors = response.getBody();

            if (!response.getStatusCode().is2xxSuccessful() || neighbors == null) {
                metrics.errors.incrementAndGet();
                metrics.recordError();
                log.warn("Search returned invalid response: status={}, bodyPresent={}",
                        response.getStatusCode(), neighbors != null);
                return;
            }

            if (neighbors.size() != neighborCount) {
                metrics.incompleteResponses.incrementAndGet();
                metrics.recordIncomplete();
                return;
            }

            metrics.successful.incrementAndGet();
            metrics.recordSuccessful();
            successfulResponse = true;
        } catch (RuntimeException e) {
            metrics.errors.incrementAndGet();
            metrics.recordError();
            log.error("Search request failed", e);
        } finally {
            long finishedNanos = System.nanoTime();

            if (measured) {
                long serviceNanos = finishedNanos - startedNanos;

                // латентность пишем по ВСЕМ завершённым: если считать только успешные,
                // деградировавшие (медленные и неполные) ответы выпадают из статистики
                // и система выглядит тем быстрее, чем ей хуже
                metrics.serviceAllNanos.record(serviceNanos);
                metrics.queueWaitNanos.record(startedNanos - enqueueNanos);
                metrics.endToEndAllNanos.record(finishedNanos - enqueueNanos);
                metrics.recordLatency(serviceNanos);

                if (successfulResponse) {
                    metrics.serviceSuccessfulNanos.record(serviceNanos);
                }
            }

            metrics.completed.incrementAndGet();
            metrics.inFlight.decrementAndGet();
            permits.release();
        }
    }

    private void waitForRequests(PhaseMetrics metrics) {
        while (metrics.inFlight.get() > 0) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for benchmark requests", e);
                throw new RuntimeException(
                        "Interrupted while waiting for benchmark requests",
                        e
                );
            }
        }
    }


    private void printResult(
            PhaseResult result,
            int targetRps,
            int maxInFlight,
            int neighborCount,
            long preparationNanos
    ) {
        PhaseMetrics metrics = result.metrics();

        double generationSeconds =
                (result.producerStoppedNanos() - result.startedNanos()) / 1_000_000_000.0;
        double totalSeconds =
                (result.finishedNanos() - result.startedNanos()) / 1_000_000_000.0;
        double drainSeconds = Math.max(0.0, totalSeconds - generationSeconds);

        long offered = metrics.scheduled.get();
        long skippedSlots = metrics.skippedSlots.get();
        long rejected = metrics.rejected.get();
        long accepted = offered - rejected;
        long started = metrics.started.get();
        long completed = metrics.completed.get();
        long successful = metrics.successful.get();
        long errors = metrics.errors.get();
        long incomplete = metrics.incompleteResponses.get();
        long submissionErrors = metrics.submissionErrors.get();

        // ВСЕ темпы считаем от одного знаменателя — окна генерации
        double offeredRps    = generationSeconds == 0.0 ? 0.0 : offered / generationSeconds;
        double acceptedRps   = generationSeconds == 0.0 ? 0.0 : accepted / generationSeconds;
        double successfulRps = generationSeconds == 0.0 ? 0.0 : successful / generationSeconds;

        double schedulePressure = (offered + skippedSlots) == 0L
                ? 0.0
                : skippedSlots * 100.0 / (offered + skippedSlots);

        double acceptedPercent   = offered == 0L ? 0.0 : accepted * 100.0 / offered;
        double rejectedPercent   = offered == 0L ? 0.0 : rejected * 100.0 / offered;
        double successfulPercent = completed == 0L ? 0.0 : successful * 100.0 / completed;
        double errorPercent      = completed == 0L ? 0.0 : errors * 100.0 / completed;
        double incompletePercent = completed == 0L ? 0.0 : incomplete * 100.0 / completed;
        double submissionErrorPercent = accepted == 0L ? 0.0 : submissionErrors * 100.0 / accepted;

        Percentiles producerLateness = Percentiles.from(metrics.producerLatenessNanos.snapshot());
        Percentiles endToEnd         = Percentiles.from(metrics.endToEndAllNanos.snapshot());
        Percentiles serviceAll       = Percentiles.from(metrics.serviceAllNanos.snapshot());
        Percentiles serviceOk        = Percentiles.from(metrics.serviceSuccessfulNanos.snapshot());
        Percentiles queueWait        = Percentiles.from(metrics.queueWaitNanos.snapshot());

        System.out.println();
        System.out.println("=== Highload summary ===");
        System.out.printf(Locale.US, "Data load and index preparation: %.3f s%n",
                preparationNanos / 1_000_000_000.0);
        System.out.println();

        System.out.println("--- Load actually applied (это и есть ось X отчёта) ---");
        System.out.println("Target RPS (setpoint only): " + targetRps);
        System.out.printf(Locale.US, "Offered RPS: %.2f%n", offeredRps);
        System.out.printf(Locale.US, "Accepted RPS: %.2f%n", acceptedRps);
        System.out.printf(Locale.US, "Successful RPS: %.2f%n", successfulRps);
        System.out.printf(Locale.US, "Generation duration: %.3f s%n", generationSeconds);
        System.out.printf(Locale.US, "Drain duration: %.3f s%n", drainSeconds);
        System.out.println();

        System.out.println("--- Generator health (проблемы КЛИЕНТА, не системы) ---");
        System.out.printf(Locale.US, "Skipped slots: %d (%.2f%% расписания)%n",
                skippedSlots, schedulePressure);
        System.out.println("Sample buffer overflow: "
                + metrics.serviceAllNanos.overflow() + " (0 = все замеры сохранены)");
        printPercentiles("Producer lateness (насколько опоздали к слоту)", producerLateness);
        System.out.println();

        System.out.println("--- Request accounting ---");
        System.out.println("Offered requests: " + offered);
        System.out.printf(Locale.US, "Accepted requests: %d/%d (%.2f%%)%n",
                accepted, offered, acceptedPercent);
        System.out.printf(Locale.US, "Rejected by in-flight limit: %d/%d (%.2f%%)%n",
                rejected, offered, rejectedPercent);
        System.out.printf(Locale.US, "Worker submission errors: %d/%d (%.2f%%)%n",
                submissionErrors, accepted, submissionErrorPercent);
        System.out.println("Started requests: " + started);
        System.out.println("Completed requests: " + completed);
        System.out.printf(Locale.US, "Successful: %d/%d (%.2f%%)%n",
                successful, completed, successfulPercent);
        System.out.printf(Locale.US, "Errors: %d/%d (%.2f%%)%n", errors, completed, errorPercent);
        System.out.printf(Locale.US, "Invalid result-count responses: %d/%d (%.2f%%)%n",
                incomplete, completed, incompletePercent);
        System.out.println();

        System.out.println("Configured max in-flight: " + maxInFlight);
        System.out.println("Observed max in-flight: " + metrics.maxInFlight.get());
        System.out.println("Neighbor count: " + neighborCount);

        printPercentiles("Service latency, ALL completed", serviceAll);
        printPercentiles("Service latency, successful only", serviceOk);
        printPercentiles("End-to-end latency, ALL completed", endToEnd);
        printPercentiles("Worker queue wait", queueWait);
    }

    private void printPercentiles(String title, Percentiles values) {
        System.out.println();
        System.out.println(title + " (n=" + values.count() + "):");
        System.out.printf(Locale.US, "min: %.3f ms%n", values.minMillis());
        System.out.printf(Locale.US, "avg: %.3f ms%n", values.averageMillis());
        System.out.printf(Locale.US, "p50: %.3f ms%n", values.p50Millis());
        System.out.printf(Locale.US, "p95: %.3f ms%n", values.p95Millis());
        System.out.printf(Locale.US, "p99: %.3f ms%n", values.p99Millis());
        System.out.printf(Locale.US, "p99.9: %.3f ms%n", values.p999Millis());
        System.out.printf(Locale.US, "max: %.3f ms%n", values.maxMillis());
    }

    private void writeTimelineCsv(PhaseResult result, int targetRps, String timelineDir) {
        if (timelineDir == null || timelineDir.isBlank()) {
            return;
        }

        PhaseMetrics metrics = result.metrics();
        Path path = Path.of(timelineDir, "highload-timeline-rps" + targetRps + ".csv");

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                writer.write("second,offered,accepted,successful,errors,incomplete,avg_latency_ms");
                writer.newLine();

                for (int second = 0; second < metrics.timelineSize; second++) {
                    long offered = metrics.offeredPerSecond.get(second);
                    long accepted = metrics.acceptedPerSecond.get(second);
                    long successful = metrics.successfulPerSecond.get(second);
                    long errors = metrics.errorsPerSecond.get(second);
                    long incomplete = metrics.incompletePerSecond.get(second);
                    long latencyCount = metrics.latencyCountPerSecond.get(second);
                    long latencySum = metrics.latencySumNanosPerSecond.get(second);

                    if (offered == 0 && accepted == 0 && successful == 0
                            && errors == 0 && incomplete == 0 && latencyCount == 0) {
                        continue;
                    }

                    double averageLatencyMs = latencyCount == 0
                            ? 0.0
                            : latencySum / (double) latencyCount / 1_000_000.0;

                    writer.write(String.format(Locale.US, "%d,%d,%d,%d,%d,%d,%.3f",
                            second, offered, accepted, successful, errors, incomplete, averageLatencyMs));
                    writer.newLine();
                }
            }

            System.out.println("Timeline saved: " + path.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Timeline write failed: {}", path, e);
        }
    }

    private void validateArguments(
            int maxInFlight,
            int targetRps,
            int warmupSeconds,
            int testSeconds,
            int neighborCount
    ) {
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }

        if (targetRps <= 0) {
            throw new IllegalArgumentException("targetRps must be positive");
        }

        if (warmupSeconds < 0) {
            throw new IllegalArgumentException("warmupSeconds must not be negative");
        }

        if (testSeconds <= 0) {
            throw new IllegalArgumentException("testSeconds must be positive");
        }

        if (neighborCount <= 0) {
            throw new IllegalArgumentException("neighborCount must be positive");
        }
    }

    private static final class PhaseMetrics {
        private final long phaseStartNanos;
        private final int timelineSize;

        private final AtomicLong scheduled = new AtomicLong();      // offered
        private final AtomicLong started = new AtomicLong();
        private final AtomicLong completed = new AtomicLong();
        private final AtomicLong successful = new AtomicLong();
        private final AtomicLong errors = new AtomicLong();
        private final AtomicLong incompleteResponses = new AtomicLong();
        private final AtomicLong rejected = new AtomicLong();
        private final AtomicLong submissionErrors = new AtomicLong();
        private final AtomicLong skippedSlots = new AtomicLong();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();

        private final Samples endToEndAllNanos;
        private final Samples serviceAllNanos;
        private final Samples serviceSuccessfulNanos;
        private final Samples queueWaitNanos;
        private final Samples producerLatenessNanos;

        private final AtomicLongArray offeredPerSecond;
        private final AtomicLongArray acceptedPerSecond;
        private final AtomicLongArray successfulPerSecond;
        private final AtomicLongArray errorsPerSecond;
        private final AtomicLongArray incompletePerSecond;
        private final AtomicLongArray latencySumNanosPerSecond;
        private final AtomicLongArray latencyCountPerSecond;

        private PhaseMetrics(long phaseStartNanos, int durationSeconds, int targetRps) {
            this.phaseStartNanos = phaseStartNanos;
            this.timelineSize = durationSeconds + DRAIN_TIMELINE_RESERVE_SECONDS;

            int sampleCapacity = Math.max(1_024, targetRps * (durationSeconds + 5));

            this.endToEndAllNanos = new Samples(sampleCapacity);
            this.serviceAllNanos = new Samples(sampleCapacity);
            this.serviceSuccessfulNanos = new Samples(sampleCapacity);
            this.queueWaitNanos = new Samples(sampleCapacity);
            this.producerLatenessNanos = new Samples(sampleCapacity);

            this.offeredPerSecond = new AtomicLongArray(timelineSize);
            this.acceptedPerSecond = new AtomicLongArray(timelineSize);
            this.successfulPerSecond = new AtomicLongArray(timelineSize);
            this.errorsPerSecond = new AtomicLongArray(timelineSize);
            this.incompletePerSecond = new AtomicLongArray(timelineSize);
            this.latencySumNanosPerSecond = new AtomicLongArray(timelineSize);
            this.latencyCountPerSecond = new AtomicLongArray(timelineSize);
        }

        private int second() {
            long elapsedNanos = System.nanoTime() - phaseStartNanos;
            if (elapsedNanos < 0) return 0;
            int second = (int) (elapsedNanos / 1_000_000_000L);
            return Math.min(second, timelineSize - 1);
        }

        private void recordOffered()    { offeredPerSecond.incrementAndGet(second()); }
        private void recordAccepted()   { acceptedPerSecond.incrementAndGet(second()); }
        private void recordSuccessful() { successfulPerSecond.incrementAndGet(second()); }
        private void recordError()      { errorsPerSecond.incrementAndGet(second()); }
        private void recordIncomplete() { incompletePerSecond.incrementAndGet(second()); }

        private void recordLatency(long nanos) {
            int second = second();
            latencySumNanosPerSecond.addAndGet(second, nanos);
            latencyCountPerSecond.incrementAndGet(second);
        }

        private void updateMaxInFlight(int value) {
            maxInFlight.accumulateAndGet(value, Math::max);
        }
    }

    /**
     * Преаллоцированный буфер замеров: ноль боксинга и ноль аллокаций на запись.
     * Прежний ConcurrentLinkedQueue<Long> создавал мусор прямо во время замера,
     * то есть сам бенч провоцировал GC-паузы, которые потом мерил как латентность.
     */
    private static final class Samples {
        private final long[] values;
        private final AtomicInteger index = new AtomicInteger();

        private Samples(int capacity) {
            this.values = new long[capacity];
        }

        private void record(long value) {
            int i = index.getAndIncrement();
            if (i < values.length) {
                values[i] = value;
            }
        }

        private long[] snapshot() {
            return Arrays.copyOf(values, Math.min(index.get(), values.length));
        }

        private long overflow() {
            return Math.max(0L, index.get() - (long) values.length);
        }
    }

    private record PhaseResult(
            PhaseMetrics metrics,
            long startedNanos,
            long producerStoppedNanos,
            long finishedNanos
    ) {}

    private record Percentiles(
            long minimum,
            double average,
            long p50,
            long p95,
            long p99,
            long p999,
            long maximum,
            int count
    ) {
        private static Percentiles from(long[] values) {
            if (values.length == 0) {
                return new Percentiles(0L, 0.0, 0L, 0L, 0L, 0L, 0L, 0);
            }

            long[] sorted = values.clone();
            Arrays.sort(sorted);

            long sum = 0L;
            for (long value : sorted) {
                sum += value;
            }

            return new Percentiles(
                    sorted[0],
                    (double) sum / sorted.length,
                    percentile(sorted, 0.50),
                    percentile(sorted, 0.95),
                    percentile(sorted, 0.99),
                    percentile(sorted, 0.999),
                    sorted[sorted.length - 1],
                    sorted.length
            );
        }

        private static long percentile(long[] sorted, double percentile) {
            int index = (int) Math.ceil(percentile * sorted.length) - 1;
            index = Math.max(0, Math.min(index, sorted.length - 1));
            return sorted[index];
        }

        private double minMillis()     { return minimum / 1_000_000.0; }
        private double averageMillis() { return average / 1_000_000.0; }
        private double p50Millis()     { return p50 / 1_000_000.0; }
        private double p95Millis()     { return p95 / 1_000_000.0; }
        private double p99Millis()     { return p99 / 1_000_000.0; }
        private double p999Millis()    { return p999 / 1_000_000.0; }
        private double maxMillis()     { return maximum / 1_000_000.0; }
    }
}