package ru.nsu.fit.vectorserver.benchmark.dataset;

import org.springframework.http.ResponseEntity;
import ru.nsu.fit.vector.common.dto.ClusterStats;
import ru.nsu.fit.vector.common.dto.LoadRequest;
import ru.nsu.fit.vector.common.dto.Neighbor;
import ru.nsu.fit.vector.common.dto.NodeStats;
import ru.nsu.fit.vector.common.dto.SearchRequest;
import ru.nsu.fit.vectorserver.VectorService;
import ru.nsu.fit.vectorserver.benchmark.BenchmarkMetrics;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class BenchmarkDatasetRunner {
    //private static final int MAX_MEASURED_QUERY_COUNT = 100;
    private static final int WARMUP_QUERY_COUNT = 10;

    private static final long INDEX_READY_TIMEOUT_MS = 30 * 60_000L;
    private static final double DISTANCE_EPSILON = 1e-6;
    private static final int READY_STABLE_POLLS = 3;

    private final VectorService service;
    private final IndexType indexType;
    private final GroundTruthFile groundTruthFile;

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
    }

    public enum IndexType {
        BRUTE_FORCE,
        JVECTOR
    }

    public void run(int neighborCount,
                    String databasePath,
                    String queriesPath,
                    String groundTruthPath,
                    boolean loadDatabase
    ) {
        System.out.println("======== BENCHMARK is running =================");
        System.out.println("Load database: " + loadDatabase);
        validateArguments(neighborCount, databasePath, queriesPath, groundTruthPath);

        File databaseFile = new File(databasePath);
        File queriesFile = new File(queriesPath);

        List<QueryVector> allQueries = readQueries(queriesFile);
        if (allQueries.isEmpty()) {
            throw new IllegalStateException("Queries file contains no vectors: " + queriesPath);
        }


        //Можно ограничить количество запросов при необходимости
        //int measuredQueryCount = Math.min(MAX_MEASURED_QUERY_COUNT, allQueries.size());
        List<QueryVector> queries = allQueries;

        long expectedVectorCount = countDatabaseRows(databaseFile);

        System.out.println("=== Dataset benchmark STARTED ===");
        System.out.println("Index type: " + indexType);
        System.out.println("Database: " + databaseFile.getAbsolutePath());
        System.out.println("Queries: " + queriesFile.getAbsolutePath());
        System.out.println("Ground truth: " + groundTruthPath);
        System.out.println("Database vectors: " + expectedVectorCount);
        System.out.println("Available queries: " + allQueries.size());
        System.out.println("Measured queries: " + queries.size());
        System.out.println("Neighbors count: " + neighborCount);
        System.out.println("=================================");




        long preparationStart = System.nanoTime();

        if (loadDatabase) {
            service.clear();

            ResponseEntity<String> loadResponse = service.load(
                    new LoadRequest(databaseFile.getAbsolutePath())
            );

            if (!loadResponse.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException(
                        "Database load failed: " + loadResponse
                );
            }

            System.out.println(
                    "Database loaded: " + loadResponse.getBody()
            );
        } else {
            System.out.println(
                    "Database loading skipped; using existing cluster data"
            );
        }

        waitUntilIndexReady(expectedVectorCount);

        long preparationEnd = System.nanoTime();

        runWarmup(queries, neighborCount);

        if (indexType == IndexType.BRUTE_FORCE) {
            runBruteForce(queries, neighborCount, groundTruthPath, preparationStart, preparationEnd);
        } else {
            runJVector(queries, neighborCount, groundTruthPath, preparationStart, preparationEnd);
        }

        System.out.println("=== Dataset benchmark FINISHED ===");
    }

    private void runBruteForce(
            List<QueryVector> queries,
            int neighborCount,
            String groundTruthPath,
            long loadStart,
            long loadEnd
    ) {
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

            if ((i + 1) % 10 == 0) {
                System.out.println(
                        "Brute force queries completed: "
                                + (i + 1)
                                + "/"
                                + queries.size()
                );
            }
        }

        groundTruthFile.write(groundTruthPath, results);

        printPerformanceMetrics(loadStart, loadEnd, totalSearchNanos, metrics, queries.size());

        System.out.println("Ground truth saved: " + groundTruthPath);
    }

    private void runJVector(
            List<QueryVector> queries,
            int neighborCount,
            String groundTruthPath,
            long loadStart,
            long loadEnd
    ) {
        Map<Long, List<GroundTruthFile.ExpectedNeighbor>> groundTruth = groundTruthFile.read(groundTruthPath);

        validateGroundTruth(
                queries,
                groundTruth,
                neighborCount
        );

        BenchmarkMetrics metrics = new BenchmarkMetrics();
        long totalSearchNanos = 0L;

        int totalIdMatches = 0;
        int totalDistanceMatches = 0;

        int perfectIdQueries = 0;
        int perfectDistanceQueries = 0;

        double totalDistanceError = 0.0;
        double maximumDistanceError = 0.0;
        int comparedDistances = 0;

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

            totalIdMatches += comparison.idMatches();
            totalDistanceMatches += comparison.distanceMatches();

            totalDistanceError += comparison.totalDistanceError();
            maximumDistanceError = Math.max(maximumDistanceError, comparison.maximumDistanceError());

            comparedDistances +=
                    comparison.comparedDistanceCount();

            if (comparison.idMatches() == neighborCount) {
                perfectIdQueries++;
            }

            if (comparison.distanceMatches() == neighborCount) {
                perfectDistanceQueries++;
            }

            if (comparison.idMatches() < neighborCount || comparison.distanceMatches() < neighborCount) {
                printMismatch(i, query.id(), expected, actual, comparison, neighborCount);
            }
        }


        printPerformanceMetrics(loadStart, loadEnd, totalSearchNanos, metrics, queries.size());
        printQualityMetrics(neighborCount, queries.size(), totalIdMatches, totalDistanceMatches,
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

        System.out.println("Warmup queries: " + warmupCount);

        for (int i = 0; i < warmupCount; i++) {
            search(queries.get(i).vector(), neighborCount);
        }
    }

    private List<Neighbor> search(float[] queryVector, int neighborCount) {
        Object response = service.search(
                new SearchRequest(queryVector, neighborCount, null)
        );
        return extractNeighbors(response);
    }

    private List<QueryVector> readQueries(File file) {
        List<QueryVector> result = new ArrayList<>();
        Set<Long> ids = new HashSet<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String header = reader.readLine();

            if (header == null) {
                throw new IllegalStateException(
                        "Queries file is empty: "
                                + file.getAbsolutePath()
                );
            }

            String line;
            int lineNumber = 1;
            int dimension = -1;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                if (line.isBlank()) continue;

                QueryVector query = parseQueryLine(line, lineNumber);

                if (!ids.add(query.id())) {
                    throw new IllegalStateException(
                            "Duplicate query ID: "
                                    + query.id()
                    );
                }

                if (dimension == -1) {
                    dimension = query.vector().length;
                } else if (query.vector().length != dimension) {
                    throw new IllegalStateException(
                            "Incorrect vector dimension at line "
                                    + lineNumber
                                    + ": "
                                    + query.vector().length
                                    + ", expected "
                                    + dimension
                    );
                }

                result.add(query);
            }
        } catch (IOException e) {
            throw new RuntimeException(
                    "Queries read error: "
                            + file.getAbsolutePath(),
                    e
            );
        }

        return result;
    }

    private QueryVector parseQueryLine(
            String line,
            int lineNumber
    ) {
        int firstSemicolon = line.indexOf(';');
        int secondSemicolon = line.indexOf(
                ';',
                firstSemicolon + 1
        );

        if (firstSemicolon < 0 || secondSemicolon < 0) {
            throw new IllegalArgumentException(
                    "Incorrect query format at line "
                            + lineNumber
            );
        }

        long id = Long.parseLong(
                line.substring(0, firstSemicolon).trim()
        );

        String url = line.substring(
                firstSemicolon + 1,
                secondSemicolon
        ).trim();

        String vectorText = line.substring(
                secondSemicolon + 1
        ).trim();

        return new QueryVector(
                id,
                url,
                parseVector(vectorText, lineNumber)
        );
    }

    private float[] parseVector(
            String value,
            int lineNumber
    ) {
        String normalized = value
                .replace("\"", "")
                .replace("[", "")
                .replace("]", "")
                .trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "Empty vector at line " + lineNumber
            );
        }

        String[] tokens = normalized.split(",\\s*");
        float[] vector = new float[tokens.length];

        for (int i = 0; i < tokens.length; i++) {
            vector[i] = Float.parseFloat(tokens[i]);

            if (!Float.isFinite(vector[i])) {
                throw new IllegalArgumentException(
                        "Non-finite vector value at line "
                                + lineNumber
                                + ", position "
                                + i
                );
            }
        }

        return vector;
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

    private long countDatabaseRows(File file) {
        long count = 0L;

        try (BufferedReader reader = new BufferedReader(
                new FileReader(file)
        )) {
            reader.readLine();

            String line;

            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) count++;
            }
        } catch (IOException e) {
            throw new RuntimeException(
                    "Database row count error: "
                            + file.getAbsolutePath(),
                    e
            );
        }

        if (count == 0L) {
            throw new IllegalStateException(
                    "Database file contains no vectors"
            );
        }

        return count;
    }

    private void waitUntilIndexReady(long expectedVectorCount) {
        long deadline = System.currentTimeMillis() + INDEX_READY_TIMEOUT_MS;
        int stablePolls = 0;

        while (System.currentTimeMillis() < deadline) {
            ClusterStats stats = service.stats().getBody();
            if (stats == null) {
                throw new IllegalStateException("Stats response body is null");
            }

            long indexed = stats.totalLiveVectors;
            long backlog = 0, enginePending = 0;
            int dirty = 0, owned = 0, active = 0;

            for (NodeStats node : stats.nodes) {
                backlog += node.applierBacklog;
                dirty += node.dirtyPartitions;
                enginePending += node.enginePendingVectors;
                owned += node.ownedPartitions;
                active += node.activePartitions;
            }

            boolean ready = indexed == expectedVectorCount
                    && backlog == 0
                    && dirty == 0
                    && enginePending == 0        // графы движка построены
                    && active == owned && owned > 0;   // ни одна партиция не застряла в REBUILDING

            System.out.println("Index state: indexed=" + indexed + "/" + expectedVectorCount
                    + ", backlog=" + backlog
                    + ", dirty=" + dirty
                    + ", enginePending=" + enginePending
                    + ", parts=" + active + "/" + owned
                    + (ready ? "  [ok " + (stablePolls + 1) + "/" + READY_STABLE_POLLS + "]" : ""));

            if (ready) {
                if (++stablePolls >= READY_STABLE_POLLS) {
                    System.out.println("Index is ready");
                    return;
                }
            } else {
                stablePolls = 0;               // условие мигнуло - стабильность считаем заново
            }

            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for index", e);
            }
        }

        throw new IllegalStateException("Index was not ready before timeout - check node logs for 'rebuild FAILED'");
    }

    private void printPerformanceMetrics(
            long loadStart,
            long loadEnd,
            long totalSearchNanos,
            BenchmarkMetrics metrics,
            int queryCount
    ) {
        double loadAndBuildMs = (loadEnd - loadStart) / 1_000_000.0;

        double totalSearchMs = totalSearchNanos / 1_000_000.0;

        double totalSearchSeconds = totalSearchNanos / 1_000_000_000.0;

        double qps = totalSearchSeconds == 0.0 ? 0.0 : queryCount / totalSearchSeconds;

        System.out.println();
        System.out.println("=== Performance ===");
        System.out.println("Index type: " + indexType);
        System.out.println("load_and_build_ms: " + loadAndBuildMs);
        System.out.println("total_search_ms: " + totalSearchMs);
        System.out.println("avg_search_ms: " + metrics.average());
        System.out.println("p95_search_ms: " + metrics.percentile(0.95));
        System.out.println("p99_search_ms: " + metrics.percentile(0.99));
        System.out.println("measured_queries: " + metrics.count());
        System.out.println("qps: " + qps);
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

        double recall = totalExpected == 0 ? 0.0 : (double) totalIdMatches / totalExpected;

        double distanceRecall = totalExpected == 0 ? 0.0 : (double) totalDistanceMatches / totalExpected;

        double averageDistanceError = comparedDistances == 0 ? 0.0 : totalDistanceError / comparedDistances;

        System.out.println();
        System.out.println("=== Quality ===");

        System.out.printf(Locale.US, "Recall@%d: %.6f%%%n", neighborCount, recall * 100.0);

        System.out.printf(Locale.US, "DistanceRecall@%d: %.6f%%%n",
                neighborCount, distanceRecall * 100.0
        );

        System.out.println("Perfect ID queries: " + perfectIdQueries + "/" + queryCount);
        System.out.println("Perfect distance queries: " + perfectDistanceQueries + "/" + queryCount);

        System.out.printf(Locale.US, "Average distance error: %.12e%n", averageDistanceError);

        System.out.printf(Locale.US, "Maximum distance error: %.12e%n", maximumDistanceError);
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

    private void validateArguments(
            int neighborCount,
            String databasePath,
            String queriesPath,
            String groundTruthPath
    ) {
        if (neighborCount <= 0) {
            throw new IllegalArgumentException(
                    "neighborCount must be positive"
            );
        }

        validateReadableFile(databasePath, "Database");
        validateReadableFile(queriesPath, "Queries");

        if (groundTruthPath == null
                || groundTruthPath.isBlank()) {

            throw new IllegalArgumentException(
                    "groundTruthPath is required"
            );
        }
    }

    private void validateReadableFile(
            String path,
            String description
    ) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(
                    description + " path is required"
            );
        }

        File file = new File(path);

        if (!file.exists() || !file.isFile() || !file.canRead()) {
            throw new IllegalArgumentException(
                    description
                            + " file cannot be read: "
                            + path
            );
        }
    }

    private record QueryVector(
            long id,
            String url,
            float[] vector
    ) {}

    private record ComparisonResult(
            int idMatches,
            int distanceMatches,
            int comparedDistanceCount,
            double totalDistanceError,
            double maximumDistanceError,
            double cutoffDistance
    ) {}
}