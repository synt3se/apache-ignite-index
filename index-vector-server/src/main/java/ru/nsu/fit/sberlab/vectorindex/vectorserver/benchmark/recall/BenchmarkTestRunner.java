package ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.recall;

import io.jhdf.HdfFile;
import io.jhdf.api.Dataset;
import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;
import ru.nsu.fit.sberlab.vectorindex.common.dto.ClusterStats;
import ru.nsu.fit.sberlab.vectorindex.common.dto.Neighbor;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeStats;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchRequest;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.VectorService;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.BenchmarkMetrics;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BenchmarkTestRunner {

    private static final int LOAD_BATCH_SIZE = 10_000;
    private static final int WARMUP_QUERY_COUNT = 10;
    private static final int MEASURED_QUERY_COUNT = 100;
    private static final long INDEX_READY_TIMEOUT_MS = 10 * 60_000L;

    private final VectorService service;

    public BenchmarkTestRunner(VectorService service) {
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

            System.out.println("=== Vector benchmark STARTED ===");
            System.out.println("Dataset: " + file.getName());
            System.out.println("vectors: " + vectorCount);
            System.out.println("queries: " + availableQueryCount);
            System.out.println("dimension: " + dimension);
            System.out.println("neighbors count: " + neighborCount);

            service.clear();

            long loadStart = System.nanoTime();

            for (int startIdx = 0; startIdx < vectorCount; startIdx += LOAD_BATCH_SIZE) {
                int currentBatchSize = Math.min(LOAD_BATCH_SIZE, vectorCount - startIdx);

                System.out.println("(～￣▽￣)～ " + startIdx + " / " + vectorCount);

                float[][] batchVectors = (float[][]) trainDataset.getData(
                        new long[]{startIdx, 0},
                        new int[]{currentBatchSize, dimension}
                );

                Map<Long, VectorObject> batch = new HashMap<>(currentBatchSize);

                for (int i = 0; i < currentBatchSize; i++) {
                    int globalIndex = startIdx + i;
                    long id = globalIndex + 1L;

                    batch.put(id, new VectorObject(
                            batchVectors[i],
                            "benchmark://vector/" + globalIndex,
                            "Source: benchmark"
                    ));
                }

                service.addAll(batch);
            }

            System.out.println("Cache loaded. Starting partition index rebuild...");
            service.rebuild();

            waitUntilIndexReady(vectorCount);

            long loadEnd = System.nanoTime();

            float[][] queries = (float[][]) testDataset.getData();
            long[][] groundTruth = (long[][]) neighborsDataset.getData();

            int warmupQueryCount = Math.min(WARMUP_QUERY_COUNT, availableQueryCount);
            int measuredQueryCount = Math.min(MEASURED_QUERY_COUNT, availableQueryCount);

            System.out.println("Warmup queries: " + warmupQueryCount);

            for (int i = 0; i < warmupQueryCount; i++) {
                service.search(new SearchRequest(queries[i], neighborCount, null));
            }

            BenchmarkMetrics searchMetrics = new BenchmarkMetrics();
            long totalSearchNanos = 0L;
            double totalRecall = 0.0;

            for (int i = 0; i < measuredQueryCount; i++) {
                long start = System.nanoTime();

                Object searchResponse = service.search(new SearchRequest(queries[i], neighborCount, null));

                long searchNanos = System.nanoTime() - start;
                totalSearchNanos += searchNanos;
                searchMetrics.add(searchNanos / 1_000_000.0);

                List<Long> foundIds = extractIdsFromResponse(searchResponse);
                long[] exactNeighbors = groundTruth[i];

                int matches = 0;
                int returnedCount = Math.min(foundIds.size(), neighborCount);

                for (int resultIndex = 0; resultIndex < returnedCount; resultIndex++) {
                    if (containsInTopK(exactNeighbors, foundIds.get(resultIndex), neighborCount)) matches++;
                }

                totalRecall += (double) matches / neighborCount;
            }

            double loadMs = (loadEnd - loadStart) / 1_000_000.0;
            double totalSearchMs = totalSearchNanos / 1_000_000.0;
            double totalSearchSeconds = totalSearchNanos / 1_000_000_000.0;
            double qps = totalSearchSeconds == 0.0 ? 0.0 : measuredQueryCount / totalSearchSeconds;
            double averageRecall = measuredQueryCount == 0 ? 0.0 : totalRecall / measuredQueryCount;

            System.out.println();
            System.out.println("=== Vector benchmark result ===");
            System.out.println("load_and_build_ms: " + loadMs);
            System.out.println("total_search_ms: " + totalSearchMs);
            System.out.println("avg_search_ms: " + searchMetrics.average());
            System.out.println("p95_search_ms: " + searchMetrics.percentile(0.95));
            System.out.println("p99_search_ms: " + searchMetrics.percentile(0.99));
            System.out.println("measured_queries: " + searchMetrics.count());
            System.out.println("qps: " + qps);
            System.out.printf("Average RECALL@%d: %.2f%%%n", neighborCount, averageRecall * 100.0);
            System.out.println("=== Vector benchmark FINISHED ===");
        } catch (Exception e) {
            throw new RuntimeException("Benchmark failed for dataset: " + hdf5Path, e);
        }
    }

    private void waitUntilIndexReady(long expectedVectors) {
        long deadline = System.currentTimeMillis() + INDEX_READY_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            ClusterStats stats = service.stats().getBody();

            long indexedVectors = stats.totalLiveVectors;
            long backlog = 0;
            int dirtyPartitions = 0;

            for (NodeStats node : stats.nodes) {
                backlog += node.applierBacklog;
                dirtyPartitions += node.dirtyPartitions;
            }

            System.out.println("Index state: " + indexedVectors + "/" + expectedVectors +
                    ", backlog=" + backlog + ", dirty=" + dirtyPartitions);

            if (indexedVectors == expectedVectors && backlog == 0 && dirtyPartitions == 0) {
                System.out.println("Index is ready");
                return;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for index", e);
            }
        }

        throw new IllegalStateException("Index was not ready before timeout");
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

        if (searchResponse instanceof org.springframework.http.ResponseEntity<?> responseEntity) {
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
}

