package ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.dataset;

import org.springframework.http.ResponseEntity;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.QueryReader;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.QueryReader.QueryVector;
import ru.nsu.fit.sberlab.vectorindex.common.dto.Neighbor;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchRequest;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.VectorService;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.BenchmarkMetrics;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class BenchmarkDatasetRunner {
    private static final int WARMUP_QUERY_COUNT = 10;
    private static final double DISTANCE_EPSILON = 1e-6;
    private final VectorService service;
    private final IndexType indexType;
    private final GroundTruthFile groundTruthFile;

    private final QueryReader queryReader;
    private boolean isPrintMismatch = false;

    private long preparationNanos = 0L;

    public BenchmarkDatasetRunner(VectorService service, IndexType indexType) {
        if (service == null) {
            throw new IllegalArgumentException("service is required");
        }

        if (indexType == null) {
            throw new IllegalArgumentException("indexType is required");
        }

        this.service = service;
        this.indexType = indexType;
        this.groundTruthFile = new GroundTruthFile();
        this.queryReader = new QueryReader();
    }

    public void run(
            int neighborCount,
            String queriesPath,
            String groundTruthPath,
            boolean isPrintMismatch,
            long preparationNanos,
            String measurementsPath
    )
    {
        this.isPrintMismatch = isPrintMismatch;
        this.preparationNanos = preparationNanos;

        validateArguments(neighborCount, groundTruthPath, measurementsPath);

        File queriesFile = new File(queriesPath);
        List<QueryVector> queries = queryReader.read(queriesFile);

        printConfiguration(queriesFile, groundTruthPath, queries.size(), neighborCount);

        runWarmup(queries, neighborCount);

        if (indexType == IndexType.BRUTE_FORCE) {
            runBruteForce(queries, neighborCount, groundTruthPath, measurementsPath);
        } else {
            runJVector(queries, neighborCount, groundTruthPath, measurementsPath);
        }

        System.out.println("=== Dataset benchmark FINISHED ===");
    }

    private void runBruteForce(
            List<QueryVector> queries,
            int neighborCount,
            String groundTruthPath,
            String measurementsPath
    ) {
        System.out.println("Brute force measurement started: " + queries.size() + " queries");
        Map<Long, List<Neighbor>> results = new LinkedHashMap<>();

        BenchmarkMetrics metrics = new BenchmarkMetrics();
        long totalSearchNanos = 0L;

        for (int i = 0; i < queries.size(); i++) {
            QueryVector query = queries.get(i);

            long searchStart = System.nanoTime();

            List<Neighbor> neighbors = search(query.vector(), neighborCount);

            long searchNanos = System.nanoTime() - searchStart;

            totalSearchNanos += searchNanos;
            metrics.add(searchNanos / 1_000_000.0);

            validateSearchResult(query.id(), neighbors, neighborCount);

            results.put(query.id(), neighbors);

            if ((i + 1) % 10 == 0 || i + 1 == queries.size()){
                System.out.println(
                        "Brute force queries completed: "
                                + (i + 1)
                                + "/"
                                + queries.size()
                );
            }
        }

        System.out.println("Brute force measurement finished");
        writeMeasurements(
                measurementsPath,
                queries,
                metrics,
                neighborCount,
                null,
                null
        );
        groundTruthFile.write(groundTruthPath, results);

        printPerformanceMetrics(totalSearchNanos, metrics, queries.size());

        System.out.println("Ground truth saved: " + groundTruthPath);
    }

    private void runJVector(
            List<QueryVector> queries,
            int neighborCount,
            String groundTruthPath,
            String measurementsPath
    ) {

        Map<Long, List<GroundTruthFile.ExpectedNeighbor>> groundTruth = groundTruthFile.read(groundTruthPath);

        validateGroundTruth(queries, groundTruth, neighborCount);


        BenchmarkMetrics metrics = new BenchmarkMetrics();
        List<Integer> idMatchesByQuery = new ArrayList<>(queries.size());

        List<Integer> distanceMatchesByQuery = new ArrayList<>(queries.size());

        long totalSearchNanos = 0L;

        int totalIdMatches = 0;
        int totalDistanceMatches = 0;

        int perfectIdQueries = 0;
        int perfectDistanceQueries = 0;

        double totalDistanceError = 0.0;
        double maximumDistanceError = 0.0;
        int comparedDistances = 0;

        System.out.println("Jvector measurement started: " + queries.size() + " queries");
        for (int i = 0; i < queries.size(); i++) {
            QueryVector query = queries.get(i);
            long searchStart = System.nanoTime();

            List<Neighbor> actual = search(query.vector(), neighborCount);

            long searchNanos = System.nanoTime() - searchStart;

            totalSearchNanos += searchNanos;
            metrics.add(searchNanos / 1_000_000.0);

            validateSearchResult(query.id(), actual, neighborCount);

            List<GroundTruthFile.ExpectedNeighbor> expected = groundTruth.get(query.id());

            ComparisonResult comparison = compare(expected, actual, neighborCount);
            idMatchesByQuery.add(comparison.idMatches());
            distanceMatchesByQuery.add(comparison.distanceMatches());

            totalIdMatches += comparison.idMatches();
            totalDistanceMatches += comparison.distanceMatches();

            totalDistanceError += comparison.totalDistanceError();
            maximumDistanceError = Math.max(maximumDistanceError, comparison.maximumDistanceError());

            comparedDistances += comparison.comparedDistanceCount();

            if (comparison.idMatches() == neighborCount) {
                perfectIdQueries++;
            }

            if (comparison.distanceMatches() == neighborCount) {
                perfectDistanceQueries++;
            }

            if (isPrintMismatch &&
                    (comparison.idMatches() < neighborCount || comparison.distanceMatches() < neighborCount)) {
                printMismatch(i, query.id(), expected, actual, comparison, neighborCount);
            }

            if ((i + 1) % 10 == 0 || i + 1 == queries.size()) {
                System.out.println("JVector queries completed: "
                        + (i + 1) + "/" + queries.size()
                );
            }
        }

        System.out.println("Jvector measurement finished");

        writeMeasurements(
                measurementsPath,
                queries,
                metrics,
                neighborCount,
                idMatchesByQuery,
                distanceMatchesByQuery
        );

        printPerformanceMetrics(totalSearchNanos, metrics, queries.size());

        printQualityMetrics(
                neighborCount, queries.size(), totalIdMatches, totalDistanceMatches,
                perfectIdQueries, perfectDistanceQueries, totalDistanceError, maximumDistanceError,
                comparedDistances
        );

    }

    private ComparisonResult compare(
            List<GroundTruthFile.ExpectedNeighbor> expected,
            List<Neighbor> actual,
            int neighborCount
    ) {
        Map<Long, Double> expectedDistanceById = new LinkedHashMap<>();

        Set<Long> expectedIds = new HashSet<>();

        for (int i = 0; i < neighborCount; i++) {
            GroundTruthFile.ExpectedNeighbor neighbor = expected.get(i);

            expectedIds.add(neighbor.id());

            expectedDistanceById.put(neighbor.id(), neighbor.distance());
        }

        double cutoffDistance = expected.get(neighborCount - 1).distance();

        double tolerance = DISTANCE_EPSILON * Math.max(1.0, Math.abs(cutoffDistance));

        int idMatches = 0;
        int distanceMatches = 0;
        int comparedDistanceCount = 0;

        double totalDistanceError = 0.0;
        double maximumDistanceError = 0.0;

        for (int i = 0; i < neighborCount; i++) {
            Neighbor neighbor = actual.get(i);

            if (expectedIds.contains(neighbor.id())) {
                idMatches++;
            }

            if (neighbor.score() <= cutoffDistance + tolerance) {
                distanceMatches++;
            }

            Double expectedDistance = expectedDistanceById.get(neighbor.id());

            if (expectedDistance != null) {
                double error = Math.abs(neighbor.score() - expectedDistance);

                comparedDistanceCount++;
                totalDistanceError += error;

                maximumDistanceError = Math.max(maximumDistanceError, error);
            }
        }

        return new ComparisonResult(
                idMatches, distanceMatches, comparedDistanceCount,
                totalDistanceError, maximumDistanceError, cutoffDistance
        );
    }

    private void runWarmup(List<QueryVector> queries, int neighborCount) {
        int warmupCount = Math.min(WARMUP_QUERY_COUNT, queries.size());

        System.out.println("Warmup started: " + warmupCount + " queries");

        for (int i = 0; i < warmupCount; i++) {
            search(queries.get(i).vector(), neighborCount);
        }

        System.out.println("Warmup finished");
    }

    private List<Neighbor> search(float[] queryVector, int neighborCount) {
        Object response = service.search(
                new SearchRequest(queryVector, neighborCount)
        );
        return extractNeighbors(response);
    }


    private void validateGroundTruth(
            List<QueryVector> queries,
            Map<Long, List<GroundTruthFile.ExpectedNeighbor>> groundTruth,
            int neighborCount
    ) {
        for (QueryVector query : queries) {
            List<GroundTruthFile.ExpectedNeighbor> neighbors =
                    groundTruth.get(query.id());

            if (neighbors == null) {
                throw new IllegalStateException("Ground truth is missing query: " + query.id());
            }

            if (neighbors.size() < neighborCount) {
                throw new IllegalStateException(
                        "Ground truth contains only "
                                + neighbors.size() + " neighbors for query " + query.id()
                                + ", required " + neighborCount
                );
            }
        }
    }

    private void validateSearchResult(
            long queryId,
            List<Neighbor> neighbors,
            int neighborCount
    ) {
        if (neighbors.size() != neighborCount) {
            throw new IllegalStateException(
                    "Incorrect result count for query " + queryId + ": " + neighbors.size()
                            + ", required " + neighborCount
            );
        }

        Set<Long> ids = new HashSet<>();

        for (Neighbor neighbor : neighbors) {
            if (!ids.add(neighbor.id())) {
                throw new IllegalStateException(
                        "Duplicate neighbor ID " + neighbor.id() + " for query " + queryId
                );
            }

            if (!Double.isFinite(neighbor.score())) {
                throw new IllegalStateException("Non-finite distance for query " + queryId);
            }
        }
    }

    private List<Neighbor> extractNeighbors(Object response) {
        Object body = response;

        if (response instanceof ResponseEntity<?> entity) {
            body = entity.getBody();
        }

        if (!(body instanceof List<?> list)) {
            throw new IllegalStateException(
                    "Unexpected search response: "
                            + (body == null
                            ? "null"
                            : body.getClass().getName())
            );
        }

        List<Neighbor> result = new ArrayList<>(list.size());

        for (Object item : list) {
            if (!(item instanceof Neighbor neighbor)) {
                throw new IllegalStateException(
                        "Unexpected search item: "
                                + (item == null
                                ? "null"
                                : item.getClass().getName())
                );
            }

            result.add(neighbor);
        }

        return result;
    }

    private void printPerformanceMetrics(
            long totalSearchNanos,
            BenchmarkMetrics metrics,
            int queryCount
    ) {
        double totalSearchMs = totalSearchNanos / 1_000_000.0;

        double totalSearchSeconds = totalSearchNanos / 1_000_000_000.0;

        double qps = totalSearchSeconds == 0.0 ? 0.0 : queryCount / totalSearchSeconds;

        System.out.println();
        System.out.println("=== Performance ===");
        System.out.println("Index type: " + indexType);
        if (preparationNanos > 0L) {
            double preparationMs = preparationNanos / 1_000_000.0;
            System.out.println("data_load_and_index_ready_ms: " + preparationMs);
        }
        System.out.println("total_search_ms: " + totalSearchMs);
        System.out.println("min_search_ms: " + metrics.min());
        System.out.println("avg_search_ms: " + metrics.average());
        System.out.println("p50_search_ms: " + metrics.percentile(0.50));
        System.out.println("p95_search_ms: " + metrics.percentile(0.95));
        System.out.println("p99_search_ms: " + metrics.percentile(0.99));
        System.out.println("max_search_ms: " + metrics.max());
        System.out.println("measured_queries: " + metrics.count());
        System.out.println("Single-client QPS: " + qps);
    }

    private void printQualityMetrics(
            int neighborCount,
            int queryCount,
            int totalIdMatches,
            int totalDistanceMatches,
            int perfectIdQueries,
            int perfectDistanceQueries,
            double totalDistanceError,
            double maximumDistanceError,
            int comparedDistances
    ) {
        int totalExpected = queryCount * neighborCount;

        double recall = totalExpected == 0 ? 0.0 :
                (double) totalIdMatches / totalExpected;

        double distanceRecall = totalExpected == 0 ? 0.0 :
                (double) totalDistanceMatches / totalExpected;

        double averageDistanceError = comparedDistances == 0 ? 0.0 :
                totalDistanceError / comparedDistances;

        double perfectIdQueryRate =
                queryCount == 0
                        ? 0.0
                        : (double) perfectIdQueries / queryCount;

        System.out.println("\n=== Quality ===");

        System.out.printf(Locale.US, "Recall@%d: %.6f%%%n", neighborCount, recall * 100.0);

        System.out.printf(Locale.US, "DistanceRecall@%d: %.6f%%%n",
                neighborCount, distanceRecall * 100.0
        );

        System.out.println("Perfect ID queries: " + perfectIdQueries + "/" + queryCount);
        System.out.printf(Locale.US, "Perfect ID query rate: %.2f%%%n",
                perfectIdQueryRate * 100.0);
        System.out.println("Perfect distance queries: " + perfectDistanceQueries + "/" + queryCount);

        System.out.println("\n---------Optional distance error shows diff Jvector " +
                "and bruteforce for equal IDs ------------");

        System.out.println(
                "Compared matching distances: "
                        + comparedDistances
                        + "/"
                        + totalExpected
        );

        System.out.printf(Locale.US, "Average distance calculation error for matching IDs: %.12e%n", averageDistanceError);

        System.out.printf(Locale.US, "Maximum distance calculation error for matching IDs: %.12e%n", maximumDistanceError);
    }

    private void printMismatch(
            int queryIndex,
            long queryId,
            List<GroundTruthFile.ExpectedNeighbor> expected,
            List<Neighbor> actual,
            ComparisonResult comparison,
            int neighborCount
    ) {
        List<Long> expectedIds = new ArrayList<>();
        List<Long> actualIds = new ArrayList<>();

        for (int i = 0; i < neighborCount; i++) {
            expectedIds.add(expected.get(i).id());
            actualIds.add(actual.get(i).id());
        }

        Set<Long> expectedSet = new HashSet<>(expectedIds);
        Set<Long> actualSet = new HashSet<>(actualIds);

        List<Long> missing = new ArrayList<>();
        List<Long> unexpected = new ArrayList<>();

        for (Long id : expectedIds) {
            if (!actualSet.contains(id)) missing.add(id);
        }

        for (Long id : actualIds) {
            if (!expectedSet.contains(id)) unexpected.add(id);
        }

        System.out.println();
        System.out.println(
                "=== Recall mismatch, queryIndex="
                        + queryIndex
                        + ", queryId="
                        + queryId
                        + " ==="
        );

        System.out.println(
                "ID recall: "
                        + comparison.idMatches()
                        + "/"
                        + neighborCount
        );

        System.out.println(
                "Distance recall: "
                        + comparison.distanceMatches()
                        + "/"
                        + neighborCount
        );

        System.out.println("Expected IDs:  " + expectedIds);
        System.out.println("Actual IDs:    " + actualIds);
        System.out.println("Missing IDs:   " + missing);
        System.out.println("Unexpected IDs:" + unexpected);

        System.out.printf(
                Locale.US,
                "Cutoff distance: %.12f%n",
                comparison.cutoffDistance()
        );
    }

    private void printConfiguration(
            File queriesFile,
            String groundTruthPath,
            int queryCount,
            int neighborCount
    ){
        System.out.println("=== Dataset benchmark STARTED ===");
        System.out.println("Index type: " + indexType);
        System.out.println("Queries: " + queriesFile.getAbsolutePath());
        System.out.println("Ground truth: " + groundTruthPath);
        System.out.println("Measured queries: " + queryCount);
        System.out.println("Neighbor count: " + neighborCount);
        System.out.println("Print mismatches: " + isPrintMismatch);
        System.out.println("=================================");
    }
    private void writeMeasurements(
            String measurementsPath,
            List<QueryVector> queries,
            BenchmarkMetrics metrics,
            int neighborCount,
            List<Integer> idMatchesByQuery,
            List<Integer> distanceMatchesByQuery
    ) {
        List<Double> latencyValues = metrics.values();

        if (latencyValues.size() != queries.size()) {
            throw new IllegalStateException(
                    "Latency count does not match query count: "
                            + latencyValues.size() + " != " + queries.size()
            );
        }

        boolean hasQualityMeasurements = idMatchesByQuery != null && distanceMatchesByQuery != null;

        if ((idMatchesByQuery == null)
                != (distanceMatchesByQuery == null)) {
            throw new IllegalArgumentException(
                    "Both quality measurement lists must be provided or both must be null"
            );
        }

        if (hasQualityMeasurements
                && (idMatchesByQuery.size() != queries.size()
                || distanceMatchesByQuery.size() != queries.size())) {
            throw new IllegalStateException(
                    "Quality measurement count does not match query count"
            );
        }

        Path path = Path.of(measurementsPath);
        Path parent = path.getParent();

        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                writer.write(
                        "query_index,query_id,index_type,latency_ms,"
                                + "id_matches,distance_matches,"
                                + "recall_at_k,distance_recall_at_k"
                );
                writer.newLine();

                for (int i = 0; i < queries.size(); i++) {
                    int idMatches = hasQualityMeasurements
                            ? idMatchesByQuery.get(i) : neighborCount;

                    int distanceMatches = hasQualityMeasurements
                            ? distanceMatchesByQuery.get(i) : neighborCount;

                    double recall = (double) idMatches / neighborCount;

                    double distanceRecall = (double) distanceMatches / neighborCount;

                    writer.write(String.format(
                            Locale.US,
                            "%d,%d,%s,%.6f,%d,%d,%.6f,%.6f",
                            i,
                            queries.get(i).id(),
                            indexType,
                            latencyValues.get(i),
                            idMatches,
                            distanceMatches,
                            recall,
                            distanceRecall
                    ));

                    writer.newLine();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Measurements write error: " + measurementsPath, e);
        }

        System.out.println("Query measurements saved: " + path.toAbsolutePath());
    }

    private void validateArguments(
            int neighborCount,
            String groundTruthPath,
            String measurementsPath
    ) {
        if (measurementsPath == null || measurementsPath.isBlank()) {
            throw new IllegalArgumentException("measurementsPath is required");
        }

        if (neighborCount <= 0) {
            throw new IllegalArgumentException(
                    "neighborCount must be positive"
            );
        }

        if (groundTruthPath == null || groundTruthPath.isBlank()) {
            throw new IllegalArgumentException("groundTruthPath is required");
        }
    }

    private record ComparisonResult(
            int idMatches,
            int distanceMatches,
            int comparedDistanceCount,
            double totalDistanceError,
            double maximumDistanceError,
            double cutoffDistance
    ) {}
    public enum IndexType {BRUTE_FORCE, JVECTOR}
}