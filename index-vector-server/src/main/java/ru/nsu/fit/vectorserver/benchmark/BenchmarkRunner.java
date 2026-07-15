package ru.nsu.fit.vectorserver.benchmark;

import org.springframework.http.ResponseEntity;
import ru.nsu.fit.vector.common.dto.*;
import ru.nsu.fit.vectorserver.VectorService;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class BenchmarkRunner {

    private static final int WARMUP_QUERY_COUNT = 10;
    private static final int MEASURED_QUERY_COUNT = 100;
    private static final long INDEX_READY_TIMEOUT_MS = 10 * 60_000L;
    private static final long QUERY_RANDOM_SEED = 42L;

    private final VectorService service;

    public BenchmarkRunner(VectorService service) {
        this.service = service;
    }

    public enum Mode {
        GROUND_TRUTH,
        MEASURE
    }

    public void run(
            int neighborCount,
            String datasetCsvPath,
            String groundTruthPath,
            Mode mode
    ) {
        File datasetFile = new File(datasetCsvPath);

        if (!datasetFile.exists()) {
            throw new IllegalArgumentException(
                    "Dataset file does not exist: " + datasetCsvPath
            );
        }

        if (neighborCount <= 0) {
            throw new IllegalArgumentException(
                    "neighborCount must be positive"
            );
        }

        System.out.println("=== Vector benchmark STARTED ===");
        System.out.println("Mode: " + mode);
        System.out.println("Dataset: " + datasetFile.getName());
        System.out.println("Ground truth: " + groundTruthPath);
        System.out.println("Neighbors count: " + neighborCount);

        service.clear();

        long loadStart = System.nanoTime();

        ResponseEntity<String> loadResponse =
                service.load(new LoadRequest(datasetCsvPath));

        if (!loadResponse.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException(
                    "Dataset load failed: " + loadResponse
            );
        }

        waitUntilIndexReady();

        long loadEnd = System.nanoTime();

        System.out.println("Dataset loaded: " + loadResponse.getBody());

        List<QueryData> queries;

        if (mode == Mode.GROUND_TRUTH) {
            List<Long> queryIds = selectQueryIds(
                    datasetCsvPath,
                    MEASURED_QUERY_COUNT
            );

            queries = readQueryVectors(
                    datasetCsvPath,
                    new HashSet<>(queryIds)
            );
        } else {
            queries = readGroundTruth(groundTruthPath);

            if (queries.isEmpty()) {
                throw new IllegalStateException(
                        "Ground truth file contains no queries"
                );
            }
        }

        int measuredQueryCount = Math.min(
                MEASURED_QUERY_COUNT,
                queries.size()
        );

        int warmupQueryCount = Math.min(
                WARMUP_QUERY_COUNT,
                measuredQueryCount
        );

        System.out.println("Selected queries: " + measuredQueryCount);
        System.out.println("Warmup queries: " + warmupQueryCount);

        for (int i = 0; i < warmupQueryCount; i++) {
            QueryData query = queries.get(i);

            searchWithoutSelf(
                    query.id,
                    query.vector,
                    neighborCount
            );
        }

        BenchmarkMetrics searchMetrics = new BenchmarkMetrics();

        long totalSearchNanos = 0L;
        int totalMatches = 0;
        int perfectQueries = 0;

        List<QueryData> generatedGroundTruth =
                new ArrayList<>(measuredQueryCount);

        for (int i = 0; i < measuredQueryCount; i++) {
            QueryData query = queries.get(i);

            long searchStart = System.nanoTime();

            List<Long> foundIds = searchWithoutSelf(
                    query.id,
                    query.vector,
                    neighborCount
            );

            long searchNanos =
                    System.nanoTime() - searchStart;

            totalSearchNanos += searchNanos;

            searchMetrics.add(
                    searchNanos / 1_000_000.0
            );

            if (mode == Mode.GROUND_TRUTH) {
                generatedGroundTruth.add(
                        new QueryData(
                                query.id,
                                query.vector,
                                foundIds
                        )
                );
            } else {
                int matches = countMatches(
                        query.expectedNeighborIds,
                        foundIds,
                        neighborCount
                );

                totalMatches += matches;

                if (matches == neighborCount) {
                    perfectQueries++;
                } else {
                    printRecallMismatch(
                            i,
                            query.id,
                            query.expectedNeighborIds,
                            foundIds,
                            matches,
                            neighborCount
                    );
                }
            }
        }

        if (mode == Mode.GROUND_TRUTH) {
            writeGroundTruth(
                    groundTruthPath,
                    neighborCount,
                    generatedGroundTruth
            );

            System.out.println();
            System.out.println(
                    "Ground truth saved: " + groundTruthPath
            );
        }

        double loadAndBuildMs =
                (loadEnd - loadStart) / 1_000_000.0;

        double totalSearchMs =
                totalSearchNanos / 1_000_000.0;

        double totalSearchSeconds =
                totalSearchNanos / 1_000_000_000.0;

        double qps = totalSearchSeconds == 0.0
                ? 0.0
                : measuredQueryCount / totalSearchSeconds;

        System.out.println();
        System.out.println("=== Vector benchmark result ===");
        System.out.println(
                "mode: " + mode
        );
        System.out.println(
                "load_and_build_ms: " + loadAndBuildMs
        );
        System.out.println(
                "total_search_ms: " + totalSearchMs
        );
        System.out.println(
                "avg_search_ms: " + searchMetrics.average()
        );
        System.out.println(
                "p95_search_ms: "
                        + searchMetrics.percentile(0.95)
        );
        System.out.println(
                "p99_search_ms: "
                        + searchMetrics.percentile(0.99)
        );
        System.out.println(
                "measured_queries: " + searchMetrics.count()
        );
        System.out.println(
                "qps: " + qps
        );

        if (mode == Mode.MEASURE) {
            int totalExpected =
                    measuredQueryCount * neighborCount;

            double averageRecall =
                    totalExpected == 0
                            ? 0.0
                            : (double) totalMatches
                            / totalExpected;

            System.out.printf(
                    "Average RECALL@%d: %.6f%%%n",
                    neighborCount,
                    averageRecall * 100.0
            );

            System.out.println(
                    "Perfect queries: "
                            + perfectQueries
                            + "/"
                            + measuredQueryCount
            );

            System.out.println(
                    "Matched neighbors: "
                            + totalMatches
                            + "/"
                            + totalExpected
            );
        }

        System.out.println(
                "=== Vector benchmark FINISHED ==="
        );
    }

    private List<Long> searchWithoutSelf(
            long queryId,
            float[] queryVector,
            int neighborCount
    ) {
        Object searchResponse = service.search(
                new SearchRequest(
                        queryVector,
                        neighborCount + 1
                )
        );

        List<Long> rawIds =
                extractIdsFromResponse(searchResponse);

        List<Long> result =
                new ArrayList<>(neighborCount);

        for (Long id : rawIds) {
            if (id == queryId) {
                continue;
            }

            result.add(id);

            if (result.size() == neighborCount) {
                break;
            }
        }

        if (result.size() != neighborCount) {
            throw new IllegalStateException(
                    "Search returned only "
                            + result.size()
                            + " neighbors for query "
                            + queryId
                            + ", required "
                            + neighborCount
            );
        }

        return result;
    }

    private List<Long> selectQueryIds(
            String datasetCsvPath,
            int queryCount
    ) {
        List<Long> ids = new ArrayList<>();

        try (BufferedReader reader =
                     new BufferedReader(
                             new FileReader(datasetCsvPath)
                     )) {

            reader.readLine();

            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                int firstComma = line.indexOf(',');

                if (firstComma < 0) {
                    continue;
                }

                long id = Long.parseLong(
                        line.substring(0, firstComma)
                );

                ids.add(id);
            }
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to read query IDs from dataset: "
                            + datasetCsvPath,
                    e
            );
        }

        if (ids.size() < queryCount) {
            throw new IllegalStateException(
                    "Dataset contains only "
                            + ids.size()
                            + " vectors, but "
                            + queryCount
                            + " queries are required"
            );
        }

        Collections.shuffle(
                ids,
                new Random(QUERY_RANDOM_SEED)
        );

        return new ArrayList<>(
                ids.subList(0, queryCount)
        );
    }

    private List<QueryData> readQueryVectors(
            String datasetCsvPath,
            Set<Long> selectedIds
    ) {
        Map<Long, QueryData> found =
                new HashMap<>(selectedIds.size());

        try (BufferedReader reader =
                     new BufferedReader(
                             new FileReader(datasetCsvPath)
                     )) {

            reader.readLine();

            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                CsvVector csvVector = parseCsvVector(line);

                if (!selectedIds.contains(csvVector.id)) {
                    continue;
                }

                found.put(
                        csvVector.id,
                        new QueryData(
                                csvVector.id,
                                csvVector.vector,
                                List.of()
                        )
                );

                if (found.size() == selectedIds.size()) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to read query vectors from dataset: "
                            + datasetCsvPath,
                    e
            );
        }

        if (found.size() != selectedIds.size()) {
            throw new IllegalStateException(
                    "Not all selected query vectors were found. "
                            + "Expected: "
                            + selectedIds.size()
                            + ", found: "
                            + found.size()
            );
        }

        List<Long> orderedIds =
                new ArrayList<>(selectedIds);

        Collections.shuffle(
                orderedIds,
                new Random(QUERY_RANDOM_SEED)
        );

        List<QueryData> queries =
                new ArrayList<>(orderedIds.size());

        for (Long id : orderedIds) {
            QueryData query = found.get(id);

            if (query != null) {
                queries.add(query);
            }
        }

        return queries;
    }

    private CsvVector parseCsvVector(String line) {
        int firstComma = line.indexOf(',');
        int secondComma =
                line.indexOf(',', firstComma + 1);

        if (firstComma < 0 || secondComma < 0) {
            throw new IllegalArgumentException(
                    "Incorrect CSV line: " + line
            );
        }

        long id = Long.parseLong(
                line.substring(0, firstComma)
        );

        String vectorString =
                line.substring(secondComma + 1)
                        .replace("\"", "")
                        .replace("[", "")
                        .replace("]", "")
                        .trim();

        String[] tokens =
                vectorString.split(",\\s*");

        float[] vector =
                new float[tokens.length];

        for (int i = 0; i < tokens.length; i++) {
            vector[i] =
                    Float.parseFloat(tokens[i]);
        }

        return new CsvVector(id, vector);
    }

    private void writeGroundTruth(
            String path,
            int neighborCount,
            List<QueryData> queries
    ) {
        try (BufferedWriter writer =
                     new BufferedWriter(
                             new FileWriter(path)
                     )) {

            writer.write(
                    "neighbor_count=" + neighborCount
            );
            writer.newLine();

            for (QueryData query : queries) {
                writer.write(
                        Long.toString(query.id)
                );

                writer.write('\t');
                writer.write(
                        vectorToString(query.vector)
                );

                writer.write('\t');
                writer.write(
                        idsToString(
                                query.expectedNeighborIds
                        )
                );

                writer.newLine();
            }
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to write ground truth: " + path,
                    e
            );
        }
    }

    private List<QueryData> readGroundTruth(String path) {
        File file = new File(path);

        if (!file.exists()) {
            throw new IllegalArgumentException(
                    "Ground truth file does not exist: " + path
            );
        }

        List<QueryData> queries = new ArrayList<>();

        try (BufferedReader reader =
                     new BufferedReader(
                             new FileReader(file)
                     )) {

            String header = reader.readLine();

            if (header == null
                    || !header.startsWith(
                    "neighbor_count="
            )) {
                throw new IllegalStateException(
                        "Incorrect ground truth header"
                );
            }

            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                String[] parts =
                        line.split("\\t", -1);

                if (parts.length != 3) {
                    throw new IllegalStateException(
                            "Incorrect ground truth line: "
                                    + line
                    );
                }

                long queryId =
                        Long.parseLong(parts[0]);

                float[] vector =
                        parseVector(parts[1]);

                List<Long> neighborIds =
                        parseIds(parts[2]);

                queries.add(
                        new QueryData(
                                queryId,
                                vector,
                                neighborIds
                        )
                );
            }
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to read ground truth: " + path,
                    e
            );
        }

        return queries;
    }

    private String vectorToString(float[] vector) {
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }

            builder.append(vector[i]);
        }

        return builder.toString();
    }

    private float[] parseVector(String value) {
        if (value.isBlank()) {
            return new float[0];
        }

        String[] tokens = value.split(",");
        float[] vector = new float[tokens.length];

        for (int i = 0; i < tokens.length; i++) {
            vector[i] = Float.parseFloat(tokens[i]);
        }

        return vector;
    }

    private String idsToString(List<Long> ids) {
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }

            builder.append(ids.get(i));
        }

        return builder.toString();
    }

    private List<Long> parseIds(String value) {
        if (value.isBlank()) {
            return List.of();
        }

        String[] tokens = value.split(",");
        List<Long> ids = new ArrayList<>(tokens.length);

        for (String token : tokens) {
            ids.add(Long.parseLong(token));
        }

        return ids;
    }

    private int countMatches(
            List<Long> expectedIds,
            List<Long> foundIds,
            int neighborCount
    ) {
        Set<Long> expected =
                new HashSet<>(
                        expectedIds.subList(
                                0,
                                Math.min(
                                        neighborCount,
                                        expectedIds.size()
                                )
                        )
                );

        Set<Long> found =
                new HashSet<>(
                        foundIds.subList(
                                0,
                                Math.min(
                                        neighborCount,
                                        foundIds.size()
                                )
                        )
                );

        int matches = 0;

        for (Long id : found) {
            if (expected.contains(id)) {
                matches++;
            }
        }

        return matches;
    }

    private void printRecallMismatch(
            int queryIndex,
            long queryId,
            List<Long> expectedIds,
            List<Long> foundIds,
            int matches,
            int neighborCount
    ) {
        Set<Long> expected =
                new HashSet<>(expectedIds);

        Set<Long> found =
                new HashSet<>(foundIds);

        List<Long> missing = new ArrayList<>();
        List<Long> unexpected = new ArrayList<>();

        for (Long id : expectedIds) {
            if (!found.contains(id)) {
                missing.add(id);
            }
        }

        for (Long id : foundIds) {
            if (!expected.contains(id)) {
                unexpected.add(id);
            }
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
                "Recall: "
                        + matches
                        + "/"
                        + neighborCount
        );

        System.out.println(
                "Expected:   " + expectedIds
        );

        System.out.println(
                "Found:      " + foundIds
        );

        System.out.println(
                "Missing:    " + missing
        );

        System.out.println(
                "Unexpected: " + unexpected
        );
    }

    private void waitUntilIndexReady() {
        long deadline =
                System.currentTimeMillis()
                        + INDEX_READY_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<ClusterStats> response =
                    service.stats();

            ClusterStats stats = response.getBody();

            if (stats == null) {
                throw new IllegalStateException(
                        "Stats response body is null"
                );
            }

            long indexedVectors =
                    stats.totalLiveVectors;

            long backlog = 0L;
            int dirtyPartitions = 0;

            for (NodeStats node : stats.nodes) {
                backlog += node.applierBacklog;
                dirtyPartitions += node.dirtyPartitions;
            }

            System.out.println(
                    "Index state: indexed="
                            + indexedVectors
                            + ", backlog="
                            + backlog
                            + ", dirty="
                            + dirtyPartitions
            );

            if (indexedVectors > 0
                    && backlog == 0
                    && dirtyPartitions == 0) {

                System.out.println("Index is ready");
                return;
            }

            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                throw new RuntimeException(
                        "Interrupted while waiting for index",
                        e
                );
            }
        }

        throw new IllegalStateException(
                "Index was not ready before timeout"
        );
    }

    private List<Long> extractIdsFromResponse(
            Object searchResponse
    ) {
        Object body = searchResponse;

        if (searchResponse
                instanceof ResponseEntity<?> responseEntity) {

            body = responseEntity.getBody();
        }

        if (!(body instanceof List<?> resultList)) {
            throw new IllegalStateException(
                    "Unexpected search response type: "
                            + (body == null
                            ? "null"
                            : body.getClass().getName())
            );
        }

        List<Long> ids =
                new ArrayList<>(resultList.size());

        for (Object item : resultList) {
            if (!(item instanceof Neighbor neighbor)) {
                throw new IllegalStateException(
                        "Unexpected search result item: "
                                + (item == null
                                ? "null"
                                : item.getClass().getName())
                );
            }

            ids.add(neighbor.id());
        }

        return ids;
    }

    private static final class CsvVector {

        private final long id;
        private final float[] vector;

        private CsvVector(long id, float[] vector) {
            this.id = id;
            this.vector = vector;
        }
    }

    private static final class QueryData {

        private final long id;
        private final float[] vector;
        private final List<Long> expectedNeighborIds;

        private QueryData(
                long id,
                float[] vector,
                List<Long> expectedNeighborIds
        ) {
            this.id = id;
            this.vector = vector;
            this.expectedNeighborIds =
                    expectedNeighborIds;
        }
    }
}