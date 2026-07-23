package ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.clients;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.nsu.fit.sberlab.vectorindex.common.dto.Neighbor;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.QueryReader;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.QueryReader.QueryVector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class BenchmarkNClientsRunner {
    private static final Logger log = LoggerFactory.getLogger(BenchmarkNClientsRunner.class);
    private static final long WORKER_SHUTDOWN_TIMEOUT_SECONDS = 90;

    private final QueryReader queryReader = new QueryReader();

    public void run(
            int clientCount,
            int warmupSeconds,
            int testSeconds,
            int neighborCount,
            String queriesPath,
            String igniteAddress,
            String cacheName,
            int dimension
    ) {
        validateArguments(
                clientCount, warmupSeconds, testSeconds, neighborCount,
                queriesPath, igniteAddress, cacheName, dimension
        );

        List<QueryVector> queries = queryReader.read(queriesPath);
        int queryDimension = queries.get(0).vector().length;
        if (queryDimension != dimension) {
            throw new IllegalArgumentException(
                    "Query vector dimension is " + queryDimension
                            + ", configured dimension is " + dimension
            );
        }

        List<BenchmarkClient> clients = new ArrayList<>(clientCount);
        ExecutorService workers = Executors.newFixedThreadPool(clientCount);

        System.out.println("=== N clients benchmark STARTED ===");
        System.out.println("Clients: " + clientCount);
        System.out.println("Queries: " + queries.size());
        System.out.println("Vector dimension: " + queries.get(0).vector().length);
        System.out.println("Neighbor count: " + neighborCount);
        System.out.println("Warmup: " + warmupSeconds + " s");
        System.out.println("Test duration: " + testSeconds + " s");
        System.out.println("===================================");

        try {
            createClients(clients, clientCount, igniteAddress, cacheName, dimension);
            boolean measure = false;

            if (warmupSeconds > 0) {
                System.out.println("Warmup started...");
                log.info("N clients warmup started: clients={}, durationSeconds={}",
                        clientCount, warmupSeconds);

                runPhase(clients, workers, queries, warmupSeconds, neighborCount, measure);

                System.out.println("Warmup finished");
                log.info("N clients warmup finished");
            }

            System.out.println("Measurement started...");
            log.info("N clients measurement started: clients={}, durationSeconds={}",
                    clientCount, testSeconds);

            measure = true;
            PhaseResult result = runPhase(clients, workers, queries, testSeconds, neighborCount, measure);
            printResult(result, clientCount, neighborCount);
        } finally {
            shutdownWorkers(workers);
            closeClients(clients);
        }

        System.out.println("=== N clients benchmark FINISHED ===");
        log.info("N clients benchmark finished");
    }

    private void createClients(
            List<BenchmarkClient> clients,
            int clientCount,
            String igniteAddress,
            String cacheName,
            int dimension
    ) {
        System.out.println("Creating " + clientCount + " Ignite clients...");

        for (int clientId = 0; clientId < clientCount; clientId++) {
            BenchmarkClient client = new BenchmarkClient(
                    clientId,
                    igniteAddress,
                    cacheName,
                    dimension
            );

            clients.add(client);
            System.out.println("Client " + clientId + " connected");
        }

        System.out.println("All clients connected");
    }

    private PhaseResult runPhase(
            List<BenchmarkClient> clients,
            ExecutorService workers,
            List<QueryVector> queries,
            int durationSeconds,
            int neighborCount,
            boolean measured
    ) {
        int clientCount = clients.size();

        CountDownLatch readyLatch = new CountDownLatch(clientCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicLong deadlineNanos = new AtomicLong();

        List<ClientMetrics> clientMetrics = new ArrayList<>(clientCount);
        List<Future<?>> futures = new ArrayList<>(clientCount);

        for (BenchmarkClient client : clients) {
            ClientMetrics metrics = new ClientMetrics(client.id());
            clientMetrics.add(metrics);

            futures.add(workers.submit(() -> {
                readyLatch.countDown();
                awaitStart(startLatch);

                runClientLoop(client, metrics, queries, clients.size(), deadlineNanos.get(), neighborCount, measured);
            }));
        }

        awaitReady(readyLatch);

        long phaseStartNanos = System.nanoTime();
        deadlineNanos.set(phaseStartNanos + TimeUnit.SECONDS.toNanos(durationSeconds));

        startLatch.countDown();

        waitForWorkers(futures);

        long phaseFinishedNanos = System.nanoTime();

        return new PhaseResult(clientMetrics, phaseStartNanos, phaseFinishedNanos, durationSeconds);
    }

    private void runClientLoop(
            BenchmarkClient client,
            ClientMetrics metrics,
            List<QueryVector> queries,
            int clientCount,
            long deadlineNanos,
            int neighborCount,
            boolean measured
    ) {
        int queryIndex = Math.floorMod(client.id(), queries.size());

        while (System.nanoTime() < deadlineNanos) {
            QueryVector query = queries.get(queryIndex);

            queryIndex = Math.floorMod(queryIndex + clientCount, queries.size());

            executeSearch(client, metrics, query, neighborCount, measured);
        }
    }

    private void executeSearch(BenchmarkClient client,
            ClientMetrics metrics,
            QueryVector query,
            int neighborCount,
            boolean measured
    ) {
        long startedNanos = System.nanoTime();
        metrics.started++; //todo mesure == false but metircs record

        try {
            List<Neighbor> neighbors = client.search(query.vector(), neighborCount);

            if (neighbors == null) {
                metrics.errors++;

                if (metrics.errorLogged.compareAndSet(false, true)) { //TODO
                    log.error("Client {} returned null response", client.id());
                }

                return;
            }

            if (neighbors.size() != neighborCount) {
                metrics.incompleteResponses++;
                return;
            }

            metrics.bytesOut += (long) query.vector().length * Float.BYTES; //todo какая та неточность
            metrics.bytesIn += estimateResponseBytes(neighbors); //todo тоже только при полном ответе
            metrics.successful++;
        } catch (RuntimeException e) {
            metrics.errors++;

            if (metrics.errorLogged.compareAndSet(false, true)) {
                log.error("Client {} search failed", client.id(), e);
            }
        } finally {
            long finishedNanos = System.nanoTime();

            if (measured) {
                metrics.latenciesNanos.add(finishedNanos - startedNanos);
            }

            metrics.completed++;
        }
    }

    private void awaitReady(CountDownLatch readyLatch) {
        try {
            readyLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for clients to become ready", e);
        }
    }

    private void awaitStart(CountDownLatch startLatch) {
        try {
            startLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Client worker interrupted before start", e);
        }
    }

    private void waitForWorkers(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for client workers", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("Client worker failed", e.getCause());
            }
        }
    }

    private long estimateResponseBytes(List<Neighbor> neighbors) {
        long total = 0L;

        for (Neighbor neighbor : neighbors) {
            total += Long.BYTES;
            total += Double.BYTES;

            if (neighbor.url() != null) {
                total += (long) neighbor.url().length() * Character.BYTES;
            }

            if (neighbor.metadata() != null) {
                total += (long) neighbor.metadata().length() * Character.BYTES;
            }
        }

        return total;
    }

    private void printResult(PhaseResult result, int clientCount, int neighborCount) {
        double measurementSeconds = result.durationSeconds();
        double totalSeconds = (result.finishedNanos() - result.startedNanos()) / 1_000_000_000.0;

        long totalStarted = 0L;
        long totalCompleted = 0L;
        long totalSuccessful = 0L;
        long totalErrors = 0L;
        long totalIncomplete = 0L;
        long totalBytesOut = 0L;
        long totalBytesIn = 0L;

        List<Long> allLatencies = new ArrayList<>();

        double minimumClientRps = Double.MAX_VALUE;
        double maximumClientRps = 0.0;

        System.out.println();
        System.out.println("=== Per-client results ===");

        for (ClientMetrics metrics : result.clientMetrics()) {
            double clientRps = measurementSeconds == 0.0 ? 0.0 : metrics.successful / measurementSeconds;

            Percentiles latency = Percentiles.from(metrics.latenciesNanos);

            minimumClientRps = Math.min(minimumClientRps, clientRps);

            maximumClientRps = Math.max(maximumClientRps, clientRps);

            totalStarted += metrics.started;
            totalCompleted += metrics.completed;
            totalSuccessful += metrics.successful;
            totalErrors += metrics.errors;
            totalIncomplete += metrics.incompleteResponses;
            totalBytesOut += metrics.bytesOut;
            totalBytesIn += metrics.bytesIn;

            allLatencies.addAll(metrics.latenciesNanos);

            System.out.printf(
                    Locale.US,
                    "Client %d: RPS=%.2f, successful=%d, errors=%d, incomplete=%d, p50=%.3f ms, p95=%.3f ms, p99=%.3f ms%n",
                    metrics.clientId,
                    clientRps,
                    metrics.successful,
                    metrics.errors,
                    metrics.incompleteResponses,
                    latency.p50Millis(),
                    latency.p95Millis(),
                    latency.p99Millis()
            );
        }

        if (result.clientMetrics().isEmpty()) {
            minimumClientRps = 0.0;
        }

        double aggregateRps = measurementSeconds == 0.0 ? 0.0 : totalSuccessful / measurementSeconds;

        double averageClientRps = clientCount == 0 ? 0.0 : aggregateRps / clientCount;

        Percentiles aggregateLatency = Percentiles.from(allLatencies);

        System.out.println();
        System.out.println("=== N clients summary ===");
        System.out.println("Clients: " + clientCount);
        System.out.println("Neighbor count: " + neighborCount);
        System.out.printf(
                Locale.US,
                "Measurement duration: %.3f s%n",
                measurementSeconds
        );
        System.out.printf(Locale.US,
                "Total duration with final requests: %.3f s%n",
                totalSeconds
        );
        System.out.printf(Locale.US, "Aggregate successful RPS: %.2f%n", aggregateRps);
        System.out.printf(Locale.US, "Average RPS per client: %.2f%n", averageClientRps);
        System.out.printf(Locale.US, "Minimum client RPS: %.2f%n", minimumClientRps);
        System.out.printf(Locale.US, "Maximum client RPS: %.2f%n", maximumClientRps);

        System.out.println();
        System.out.println("Started requests: " + totalStarted);
        System.out.println("Completed requests: " + totalCompleted);
        System.out.println("Successful requests: " + totalSuccessful);
        System.out.println("Errors: " + totalErrors);
        System.out.println("Incomplete responses: " + totalIncomplete);
        System.out.println("Bytes out: " + totalBytesOut);
        System.out.println("Bytes in: " + totalBytesIn);

        printPercentiles("Aggregate latency", aggregateLatency);

        log.info(
                "N clients result: clients={}, aggregateRps={}, averageClientRps={}, minClientRps={}, maxClientRps={}, started={}, completed={}, successful={}, errors={}, incomplete={}",
                clientCount,
                formatNumber(aggregateRps),
                formatNumber(averageClientRps),
                formatNumber(minimumClientRps),
                formatNumber(maximumClientRps),
                totalStarted,
                totalCompleted,
                totalSuccessful,
                totalErrors,
                totalIncomplete
        );
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

    private void shutdownWorkers(ExecutorService workers) {
        workers.shutdown();

        try {
            if (!workers.awaitTermination(
                    WORKER_SHUTDOWN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            )) {
                log.warn(
                        "N clients worker pool did not stop in {} seconds",
                        WORKER_SHUTDOWN_TIMEOUT_SECONDS
                );

                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();

            log.error("Interrupted while stopping N clients worker pool", e);
        }
    }

    private void closeClients(List<BenchmarkClient> clients) {
        for (BenchmarkClient client : clients) {
            try {
                client.close();
            } catch (RuntimeException e) {
                log.warn("Failed to close benchmark client {}", client.id(), e);
            }
        }
    }

    private String formatNumber(double value) {return String.format(Locale.US, "%.2f", value);}

    private void validateArguments(int clientCount, int warmupSeconds, int testSeconds,
            int neighborCount, String queriesPath, String igniteAddress, String cacheName,
            int dimension
    ) {
        if (clientCount <= 0) throw new IllegalArgumentException("Client count must be positive");
        if (warmupSeconds < 0) throw new IllegalArgumentException("Warmup seconds must not be negative");
        if (testSeconds <= 0) throw new IllegalArgumentException("Test seconds must be positive");
        if (neighborCount <= 0) throw new IllegalArgumentException("Neighbor count must be positive");
        if (queriesPath == null || queriesPath.isBlank()) throw new IllegalArgumentException("Queries path is required");
        if (igniteAddress == null || igniteAddress.isBlank()) throw new IllegalArgumentException("Ignite address is required");
        if (cacheName == null || cacheName.isBlank()) throw new IllegalArgumentException("Cache name is required");
        if (dimension <= 0) throw new IllegalArgumentException("Dimension must be positive");
    }

    private static final class ClientMetrics {
        private final int clientId;
        private long started;
        private long completed;
        private long successful;
        private long errors;
        private long incompleteResponses;
        private long bytesOut;
        private long bytesIn;

        private final List<Long> latenciesNanos = new ArrayList<>();

        private final AtomicBoolean errorLogged = new AtomicBoolean();

        private ClientMetrics(int clientId) {
            this.clientId = clientId;
        }
    }

    private record PhaseResult(
            List<ClientMetrics> clientMetrics,
            long startedNanos,
            long finishedNanos,
            int durationSeconds
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
        private static Percentiles from(List<Long> values) {
            if (values.isEmpty()) {
                return new Percentiles(0L, 0.0, 0L, 0L, 0L,
                        0L, 0L);
            }

            long[] sorted = new long[values.size()];
            long sum = 0L;

            for (int i = 0; i < values.size(); i++) {
                long value = values.get(i);
                sorted[i] = value;
                sum += value;
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

        private double minMillis() {return minimum / 1_000_000.0;}
        private double averageMillis() {return average / 1_000_000.0;}
        private double p50Millis() {return p50 / 1_000_000.0;}
        private double p95Millis() {return p95 / 1_000_000.0;}
        private double p99Millis() {return p99 / 1_000_000.0;}
        private double p999Millis() {return p999 / 1_000_000.0;}
        private double maxMillis() {return maximum / 1_000_000.0;}
    }
}