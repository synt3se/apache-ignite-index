package ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.recall;

import io.jhdf.HdfFile;
import io.jhdf.api.Dataset;
import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;
import org.springframework.http.ResponseEntity;
import ru.nsu.fit.sberlab.vectorindex.common.dto.Neighbor;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchRequest;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.VectorService;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.BenchmarkMetrics;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.DatabaseLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BenchmarkAnnRunner {

    private static final int LOAD_BATCH_SIZE = 10_000;
    private static final int WARMUP_QUERY_COUNT = 10;
    private static final int MEASURED_QUERY_COUNT = 100;
    private final VectorService service;

    public BenchmarkAnnRunner(VectorService service) {
        this.service = service;
    }

    public void run(int neighborCount, String hdf5Path) {
        File file = new File(hdf5Path);
        if (!file.exists()) throw new IllegalArgumentException("Incorrect dataset filepath: " + hdf5Path);

        try (HdfFile hdfFile = new HdfFile(file)) {
            Dataset trainDataset = hdfFile.getDatasetByPath("train");
            int[] trainDims = trainDataset.getDimensions();
            int vectorCount = trainDims[0];
            int dimension = trainDims[1];

            Dataset testDataset = hdfFile.getDatasetByPath("test");
            int availableQueryCount = testDataset.getDimensions()[0];

            Dataset neighborsDataset = hdfFile.getDatasetByPath("neighbors");
            int maxNeighbors = neighborsDataset.getDimensions()[1];

            neighborCount = Math.min(neighborCount, maxNeighbors);

            int warmupQueryCount = Math.min(WARMUP_QUERY_COUNT, availableQueryCount);
            int measuredQueryCount = Math.min(MEASURED_QUERY_COUNT, availableQueryCount);

            System.out.println("=== ANN Benchmark STARTED ===");
            System.out.println("Dataset: " + file.getName());
            System.out.println("vectors: " + vectorCount);
            System.out.println("queries: " + measuredQueryCount);
            System.out.println("dimension: " + dimension);
            System.out.println("neighbors count: " + neighborCount);
            System.out.println("=================================");
            long loadMs = prepareIndex(trainDataset, vectorCount, dimension);

            float[][] queries = (float[][]) testDataset.getData();
            long[][] groundTruth = (long[][]) neighborsDataset.getData();

            runWarmup(queries, warmupQueryCount, neighborCount);

            MeasurementResult measurementResult = runMeasurement(
                    queries,
                    groundTruth,
                    measuredQueryCount,
                    neighborCount
            );

            printResult(loadMs, measuredQueryCount, neighborCount, measurementResult);

        } catch (Exception e) {
            throw new RuntimeException("Benchmark failed for dataset: " + hdf5Path, e);
        }
    }


    private boolean containsInTopK(long[] exactNeighbors, long foundId, int neighborCount) {
        int limit = Math.min(neighborCount, exactNeighbors.length);

        for (int i = 0; i < limit; i++) {
            long expectedId = exactNeighbors[i] + 1L;
            if (expectedId == foundId) return true;
        }

        return false;
    }

    private List<Long> extractIdsFromResponse(Object searchResponse) {
        Object body = searchResponse;

        if (searchResponse instanceof ResponseEntity<?> responseEntity) {
            body = responseEntity.getBody();
        }

        if (!(body instanceof List<?> resultList)) {
            throw new IllegalStateException("Unexpected search response type: " +
                    (body == null ? "null" : body.getClass().getName()));
        }

        List<Long> ids = new ArrayList<>(resultList.size());

        for (Object item : resultList) {
            if (!(item instanceof Neighbor neighbor)) {
                throw new IllegalStateException("Unexpected search result item: " +
                        (item == null ? "null" : item.getClass().getName()));
            }

            ids.add(neighbor.id());
        }

        return ids;
    }
    private long prepareIndex(
            Dataset trainDataset,
            int vectorCount,
            int dimension
    ) {
        System.out.println("Clearing cache and local indexes...");
        service.clear();
        System.out.println("Cache and local indexes cleared");
        System.out.println("Loading train vectors...");

        long preparationStart = System.nanoTime();

        for (int startIdx = 0; startIdx < vectorCount; startIdx += LOAD_BATCH_SIZE) {
            int currentBatchSize = Math.min(
                    LOAD_BATCH_SIZE,
                    vectorCount - startIdx
            );

            float[][] batchVectors = (float[][]) trainDataset.getData(
                    new long[]{startIdx, 0},
                    new int[]{currentBatchSize, dimension}
            );

            Map<Long, VectorObject> batch = new HashMap<>(currentBatchSize);

            for (int i = 0; i < currentBatchSize; i++) {
                int globalIndex = startIdx + i;
                long id = globalIndex + 1L;

                batch.put(
                        id,
                        new VectorObject(
                                batchVectors[i],
                                "benchmark://vector/" + globalIndex,
                                "Source: benchmark"
                        )
                );
            }

            service.addAll(batch);

            int loaded = startIdx + currentBatchSize;
            double progress = loaded * 100.0 / vectorCount;

            System.out.printf(
                    "Loading vectors: %d/%d (%.1f%%)%n",
                    loaded,
                    vectorCount,
                    progress
            );
        }

        service.rebuild();
        System.out.println("Vector loading finished");
        System.out.println("Starting partition index rebuild...");

        DatabaseLoader loader = new DatabaseLoader(service);
        loader.waitUntilIndexReady(vectorCount);
        System.out.println("Index preparation finished");

        return System.nanoTime() - preparationStart;
    }
    private void runWarmup(float[][] queries, int warmupQueryCount, int neighborCount) {
        System.out.println("Warmup started...");

        for (int i = 0; i < warmupQueryCount; i++) {
            service.search(new SearchRequest(queries[i], neighborCount));
        }

        System.out.println("Warmup finished");
    }
    private MeasurementResult runMeasurement(
            float[][] queries,
            long[][] groundTruth,
            int measuredQueryCount,
            int neighborCount
    ) {
        BenchmarkMetrics searchMetrics = new BenchmarkMetrics();
        long totalSearchNanos = 0L;
        double totalRecall = 0.0;
        int incompleteResponses = 0;
        System.out.println("Measurement started...");

        for (int i = 0; i < measuredQueryCount; i++) {
            long start = System.nanoTime();

            Object searchResponse = service.search(
                    new SearchRequest(
                            queries[i],
                            neighborCount
                    )
            );

            long searchNanos = System.nanoTime() - start;

            totalSearchNanos += searchNanos;
            searchMetrics.add(
                    searchNanos / 1_000_000.0
            );

            List<Long> foundIds = extractIdsFromResponse(searchResponse);
            if (foundIds.size() < neighborCount) {
                incompleteResponses++;
            }

            long[] exactNeighbors = groundTruth[i];

            int matches = 0;
            int returnedCount = Math.min(
                    foundIds.size(),
                    neighborCount
            );

            for (int resultIndex = 0; resultIndex < returnedCount; resultIndex++) {
                if (containsInTopK(
                        exactNeighbors,
                        foundIds.get(resultIndex),
                        neighborCount
                )) {
                    matches++;
                }
            }

            totalRecall += (double) matches / neighborCount;
        }

        System.out.println("Measurement finished");

        double averageRecall =
                measuredQueryCount == 0
                        ? 0.0
                        : totalRecall / measuredQueryCount;

        return new MeasurementResult(
                searchMetrics,
                totalSearchNanos,
                averageRecall,
                incompleteResponses
        );
    }
    private record MeasurementResult(
            BenchmarkMetrics searchMetrics,
            long totalSearchNanos,
            double averageRecall,
            int incompleteResponses
    ) {}
    private void printResult(
            long preparationNanos,
            int measuredQueryCount,
            int neighborCount,
            MeasurementResult result
    ) {
        double preparationMs =
                preparationNanos / 1_000_000.0;

        double totalSearchMs =
                result.totalSearchNanos() / 1_000_000.0;

        double totalSearchSeconds =
                result.totalSearchNanos() / 1_000_000_000.0;

        double qps =
                totalSearchSeconds == 0.0
                        ? 0.0
                        : measuredQueryCount / totalSearchSeconds;

        System.out.println();
        System.out.println("=== ANN Benchmark summary ===");
        System.out.println("data_load_and_index_ready_ms: " + preparationMs);
        System.out.println("total_search_ms: " + totalSearchMs);
        System.out.println("avg_search_ms: " + result.searchMetrics().average());
        System.out.println("p50_search_ms: " + result.searchMetrics().percentile(0.50));
        System.out.println("p95_search_ms: " + result.searchMetrics().percentile(0.95));
        System.out.println("p99_search_ms: " + result.searchMetrics().percentile(0.99));
        System.out.println("measured_queries: " + result.searchMetrics().count());
        System.out.println("incomplete_responses: " + result.incompleteResponses());
        System.out.println("qps: " + qps);
        System.out.printf("Recall@%d: %.2f%%%n", neighborCount, result.averageRecall() * 100.0);
        System.out.println("=== ANN Benchmark FINISHED ===");
    }
}

