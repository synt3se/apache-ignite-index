package ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.highload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import ru.nsu.fit.sberlab.vectorindex.common.dto.Neighbor;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchRequest;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.VectorService;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.QueryReader;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.QueryReader.QueryVector;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class BenchmarkHighLoadRunner {
    private static final Logger log = LoggerFactory.getLogger(BenchmarkHighLoadRunner.class);
    private static final long WORKER_SHUTDOWN_TIMEOUT_SECONDS = 90;

    private final VectorService service;


    public BenchmarkHighLoadRunner(VectorService service) {
        if (service == null) throw new IllegalArgumentException("service is required");
        this.service = service;
    }

    public void run(
            int maxInFlight, //Пул потоков
            int targetRps, //Сколько запросов в секунду пытаемся создать
            int warmupSeconds,
            int testSeconds, //продолжительность основной измеряемой фазы
            int neighborCount, //Сколько соседей просим вернуть
            String queriesPath,
            long preparationNanos,
            List<QueryVector> queries
    ) {
        log.info("Starting highload benchmark: maxInFlight={}, targetRps={}, warmupSeconds={}, testSeconds={}, neighborCount={}, queriesPath={}",
                maxInFlight, targetRps, warmupSeconds, testSeconds, neighborCount, queriesPath);

        validateArguments(maxInFlight, targetRps, warmupSeconds, testSeconds, neighborCount);

        log.info("Loaded {} queries with vector dimension {}", queries.size(), queries.get(0).vector().length);

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
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        try {
            boolean measured = false;
            if (warmupSeconds > 0) {
                System.out.println("Warmup started...");
                log.info("Warmup started");

                runPhase(
                        scheduler,
                        workers,
                        queries,
                        maxInFlight,
                        targetRps,
                        warmupSeconds,
                        neighborCount,
                        measured
                );

                log.info("Warmup finished");
                System.out.println("Warmup finished");
            }


            System.out.println("Measurement started...");
            log.info("Measurement started");

            measured = true;
            PhaseResult result = runPhase(
                    scheduler,
                    workers,
                    queries,
                    maxInFlight,
                    targetRps,
                    testSeconds,
                    neighborCount,
                    measured
            );

            printResult(result, targetRps, maxInFlight, neighborCount, preparationNanos);
        } finally {
            log.info("Stopping benchmark executors");

            scheduler.shutdownNow();
            workers.shutdown();

            try {
                if (!workers.awaitTermination(
                        WORKER_SHUTDOWN_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                )) {
                    log.warn("Worker pool did not stop in {} seconds, forcing shutdown", WORKER_SHUTDOWN_TIMEOUT_SECONDS);
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
            ScheduledExecutorService scheduler,
            ExecutorService workers,
            List<QueryVector> queries,
            int maxInFlight,
            int targetRps,
            int durationSeconds,
            int neighborCount,
            boolean measured
    ) {
        log.info("Phase started: measured={}, durationSeconds={}, targetRps={}", measured, durationSeconds, targetRps);

        PhaseMetrics metrics = new PhaseMetrics();
        Semaphore permits = new Semaphore(maxInFlight);
        AtomicInteger querySequence = new AtomicInteger();


        long intervalNanos = Math.max(1L, 1_000_000_000L / targetRps);
        long phaseStartNanos = System.nanoTime();

        ScheduledFuture<?> producer = scheduler.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            long previous = metrics.previousScheduledNanos.getAndSet(now);

            if (measured && previous != 0L) {
                metrics.producerIntervalsNanos.add(now - previous);
            }

            metrics.scheduled.incrementAndGet();

            if (!permits.tryAcquire()) {
                metrics.rejected.incrementAndGet();
                return;
            }

            int currentInFlight = metrics.inFlight.incrementAndGet();
            metrics.updateMaxInFlight(currentInFlight);

            int queryIndex = Math.floorMod(querySequence.getAndIncrement(), queries.size());
            QueryVector query = queries.get(queryIndex);
            long enqueueNanos = System.nanoTime();

            try {
                workers.execute(() -> executeSearch(
                        query,
                        neighborCount,
                        measured,
                        enqueueNanos,
                        permits,
                        metrics
                ));
            } catch (RuntimeException e) {
                metrics.inFlight.decrementAndGet();
                metrics.submissionErrors.incrementAndGet();
                permits.release();
                log.error("Failed to submit search request to worker pool", e);
            }
        }, 0L, intervalNanos, TimeUnit.NANOSECONDS);

        try {
            Thread.sleep(durationSeconds * 1_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            producer.cancel(false);
            log.error("Highload benchmark phase interrupted", e);
            throw new RuntimeException("Highload benchmark interrupted", e);
        }

        producer.cancel(false);
        long producerStoppedNanos = System.nanoTime();

        log.info("Request producer stopped, waiting for {} in-flight requests", metrics.inFlight.get());

        waitForRequests(metrics);

        long phaseFinishedNanos = System.nanoTime();

        log.info("Phase finished: measured={}, scheduled={}, completed={}, successful={}, errors={}, incomplete={}, rejected={}",
                measured, metrics.scheduled.get(), metrics.completed.get(), metrics.successful.get(),
                metrics.errors.get(), metrics.incompleteResponses.get(), metrics.rejected.get());

        return new PhaseResult(metrics, phaseStartNanos, producerStoppedNanos, phaseFinishedNanos);
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
                log.warn("Search returned invalid response: status={}, bodyPresent={}",
                        response.getStatusCode(), neighbors != null);
                return;
            }

            if (neighbors.size() != neighborCount) {
                metrics.incompleteResponses.incrementAndGet();
                return;
            }
            metrics.successful.incrementAndGet();
            successfulResponse = true;
        } catch (RuntimeException e) {
            metrics.errors.incrementAndGet();
            log.error("Search request failed", e);
        } finally {
            long finishedNanos = System.nanoTime();

            if (measured && successfulResponse) {
                metrics.serviceLatenciesNanos.add(
                        finishedNanos - startedNanos
                );

                metrics.queueWaitNanos.add(
                        startedNanos - enqueueNanos
                );

                metrics.endToEndLatenciesNanos.add(
                        finishedNanos - enqueueNanos
                );
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
                (result.producerStoppedNanos() - result.startedNanos())
                        / 1_000_000_000.0;

        double totalSeconds =
                (result.finishedNanos() - result.startedNanos())
                        / 1_000_000_000.0;

        double drainSeconds =
                Math.max(0.0, totalSeconds - generationSeconds);

        double actualGeneratedRps = generationSeconds == 0.0
                ? 0.0
                : metrics.scheduled.get() / generationSeconds;

        double startedSearchRps = generationSeconds == 0.0
                ? 0.0
                : metrics.started.get() / generationSeconds;

        double actualSuccessfulRPS = totalSeconds == 0.0
                ? 0.0
                : metrics.successful.get() / totalSeconds;

        long generated = metrics.scheduled.get();
        long rejected = metrics.rejected.get();
        long accepted = generated - rejected;

        long started = metrics.started.get();
        long completed = metrics.completed.get();
        long successful = metrics.successful.get();
        long errors = metrics.errors.get();
        long invalidResultCount = metrics.incompleteResponses.get();
        long submissionErrors = metrics.submissionErrors.get();

        double acceptedPercent = generated == 0L
                ? 0.0
                : accepted * 100.0 / generated;

        double rejectedPercent = generated == 0L
                ? 0.0
                : rejected * 100.0 / generated;

        double successfulPercent = completed == 0L
                ? 0.0
                : successful * 100.0 / completed;

        double errorPercent = completed == 0L
                ? 0.0
                : errors * 100.0 / completed;

        double invalidResultCountPercent = completed == 0L
                ? 0.0
                : invalidResultCount * 100.0 / completed;

        double submissionErrorPercent = accepted == 0L
                ? 0.0
                : submissionErrors * 100.0 / accepted;

        Percentiles endToEndLatency = Percentiles.from(metrics.endToEndLatenciesNanos);

        Percentiles serviceLatency = Percentiles.from(metrics.serviceLatenciesNanos);

        Percentiles queueWait = Percentiles.from(metrics.queueWaitNanos);

        System.out.println();
        System.out.println("=== Highload summary ===");
        Percentiles producerInterval =
                Percentiles.from(metrics.producerIntervalsNanos);

        printPercentiles("Producer inter-arrival interval", producerInterval);

        System.out.printf(
                Locale.US,
                "Data load and index preparation: %.3f s%n",
                preparationNanos / 1_000_000_000.0
        );
        System.out.println("Target RPS: " + targetRps);
        System.out.printf(Locale.US, "Actual generated RPS: %.2f%n", actualGeneratedRps);
        System.out.printf(Locale.US, "Started search RPS: %.2f%n", startedSearchRps);
        System.out.printf(Locale.US, "Actual successful RPS: %.2f%n", actualSuccessfulRPS);
        System.out.printf(Locale.US, "Generation duration: %.3f s%n", generationSeconds);
        System.out.printf(Locale.US, "Drain duration: %.3f s%n", drainSeconds);
        System.out.printf(Locale.US, "Total phase duration: %.3f s%n", totalSeconds);
        System.out.println();

        System.out.println("Generated requests: " + generated);

        System.out.printf(Locale.US, "Accepted requests: %d/%d (%.2f%%)%n", accepted,
                generated, acceptedPercent);

        System.out.printf(Locale.US, "Rejected by in-flight limit: %d/%d (%.2f%%)%n",
                rejected, generated, rejectedPercent
        );

        System.out.printf(
                Locale.US,
                "Worker submission errors: %d/%d (%.2f%%)%n",
                submissionErrors,
                accepted,
                submissionErrorPercent
        );

        System.out.println("Started requests: " + started);
        System.out.println("Completed requests: " + completed);

        System.out.printf(Locale.US, "Successful requests: %d/%d (%.2f%%)%n",
                successful, completed, successfulPercent
        );

        System.out.printf(Locale.US, "Errors: %d/%d (%.2f%%)%n", errors, completed, errorPercent);

        System.out.printf(Locale.US, "Invalid result-count responses: %d/%d (%.2f%%)%n",
                invalidResultCount, completed, invalidResultCountPercent
        );
        System.out.println();

        System.out.println("Configured max in-flight: " + maxInFlight);
        System.out.println("Observed max in-flight: " + metrics.maxInFlight.get());
        System.out.println("Neighbor count: " + neighborCount);

        printPercentiles("Successful end-to-end latency", endToEndLatency);
        printPercentiles("Successful service latency", serviceLatency);
        printPercentiles("Worker queue wait", queueWait);
    }

    private void printPercentiles(String title, Percentiles values) {
        System.out.println();
        System.out.println(title + ":");
        System.out.printf(Locale.US, "min: %.3f ms%n", values.minMillis());
        System.out.printf(Locale.US, "avg: %.3f ms%n", values.averageMillis());
        System.out.printf(Locale.US, "p50: %.3f ms%n", values.p50Millis());
        System.out.printf(Locale.US, "p95: %.3f ms%n", values.p95Millis());
        System.out.printf(Locale.US, "p99: %.3f ms%n", values.p99Millis());
        System.out.printf(Locale.US, "p99.9: %.3f ms%n", values.p999Millis());
        System.out.printf(Locale.US, "max: %.3f ms%n", values.maxMillis());
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
        private final AtomicLong scheduled = new AtomicLong();
        private final AtomicLong started = new AtomicLong();
        private final AtomicLong completed = new AtomicLong();
        private final AtomicLong successful = new AtomicLong();
        private final AtomicLong errors = new AtomicLong();
        private final AtomicLong incompleteResponses = new AtomicLong();
        private final AtomicLong rejected = new AtomicLong();
        private final AtomicLong submissionErrors = new AtomicLong();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();

        private final AtomicLong previousScheduledNanos = new AtomicLong();
        private final ConcurrentLinkedQueue<Long> producerIntervalsNanos =
                new ConcurrentLinkedQueue<>();

        private final ConcurrentLinkedQueue<Long> endToEndLatenciesNanos =
                new ConcurrentLinkedQueue<>();

        private final ConcurrentLinkedQueue<Long> serviceLatenciesNanos =
                new ConcurrentLinkedQueue<>();

        private final ConcurrentLinkedQueue<Long> queueWaitNanos =
                new ConcurrentLinkedQueue<>();
        private void updateMaxInFlight(int value) {
            maxInFlight.accumulateAndGet(value, Math::max);
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
            long maximum
    ) {
        private static Percentiles from(ConcurrentLinkedQueue<Long> values) {
            if (values.isEmpty()) {
                return new Percentiles(0L, 0.0, 0L, 0L, 0L, 0L, 0L);
            }

            long[] sorted = new long[values.size()];
            int index = 0;
            long sum = 0L;

            for (Long value : values) {
                sorted[index++] = value;
                sum += value;
            }

            if (index != sorted.length) {
                sorted = Arrays.copyOf(sorted, index);
            }

            Arrays.sort(sorted);

            return new Percentiles(
                    sorted[0],
                    (double) sum / sorted.length,
                    percentile(sorted, 0.50),
                    percentile(sorted, 0.95),
                    percentile(sorted, 0.99),
                    percentile(sorted, 0.999),
                    sorted[sorted.length - 1]
            );
        }

        private static long percentile(long[] sorted, double percentile) {
            int index = (int) Math.ceil(percentile * sorted.length) - 1;
            index = Math.max(0, Math.min(index, sorted.length - 1));
            return sorted[index];
        }

        private double minMillis() {
            return minimum / 1_000_000.0;
        }

        private double averageMillis() {
            return average / 1_000_000.0;
        }

        private double p50Millis() {
            return p50 / 1_000_000.0;
        }

        private double p95Millis() {
            return p95 / 1_000_000.0;
        }

        private double p99Millis() {
            return p99 / 1_000_000.0;
        }

        private double p999Millis() {
            return p999 / 1_000_000.0;
        }

        private double maxMillis() {
            return maximum / 1_000_000.0;
        }
    }
}