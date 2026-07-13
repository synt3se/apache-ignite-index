package ru.nsu.fit.vectorserver.benchmark;

import io.jhdf.HdfFile;
import io.jhdf.api.Dataset;
import ru.nsu.fit.vector.common.dto.AddRequest;
import ru.nsu.fit.vector.common.dto.Neighbor;
import ru.nsu.fit.vector.common.dto.SearchRequest;
import ru.nsu.fit.vectorserver.VectorService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class BenchmarkRunner {

    private final VectorService service;

    public BenchmarkRunner(VectorService service) {
        this.service = service;
    }

    public void run(int neighborCount, String hdf5Path) {
        File file = new File(hdf5Path);

        if (!file.exists()) {
            throw new IllegalArgumentException(
                    "Incorrect dataset filepath: " + hdf5Path
            );
        }

        try (HdfFile hdfFile = new HdfFile(file)) {
            Dataset trainDataset = hdfFile.getDatasetByPath("train");
            int[] trainDims = trainDataset.getDimensions();
            int vectorCount = trainDims[0];
            int dimension = trainDims[1];

            Dataset testDataset = hdfFile.getDatasetByPath("test");
            int[] testDims = testDataset.getDimensions();
            int queryCount = testDims[0];

            Dataset neighborsDataset = hdfFile.getDatasetByPath("neighbors");
            int maxNeighbors = neighborsDataset.getDimensions()[1];

            neighborCount = Math.min(neighborCount, maxNeighbors);

            System.out.println("=== Vector benchmark STARTED ===");
            System.out.println("Dataset: " + file.getName());
            System.out.println("vectors: " + vectorCount);
            System.out.println("queries: " + queryCount);
            System.out.println("dimension: " + dimension);
            System.out.println("neighbors count: " + neighborCount);

            service.clear();

            long loadStart = System.nanoTime();
            int batchSize = 10_000;

            for (int startIdx = 0; startIdx < vectorCount; startIdx += batchSize) {
                int currentBatchSize = Math.min(batchSize, vectorCount - startIdx);

                System.out.println("(～￣▽￣)～ " + startIdx + " / " + vectorCount);

                float[][] batchVectors = (float[][]) trainDataset.getData(
                        new long[]{startIdx, 0},
                        new int[]{currentBatchSize, dimension}
                );

                for (int i = 0; i < currentBatchSize; i++) {
                    int globalIndex = startIdx + i;

                    AddRequest request = new AddRequest(
                            batchVectors[i],
                            "benchmark://vector/" + globalIndex,
                            "Source: benchmark"
                    );

                    service.add(request);
                }
            }

            long loadEnd = System.nanoTime();

            float[][] queries = (float[][]) testDataset.getData();
            long[][] groundTruth = (long[][]) neighborsDataset.getData();

            BenchmarkMetrics searchMetrics = new BenchmarkMetrics();

            int warmupQueries = Math.min(10, queryCount);
            System.out.println("Warmup queries: " + warmupQueries);

            for (int i = 0; i < warmupQueries; i++) {
                service.search(
                        new SearchRequest(queries[i], neighborCount)
                );
            }

            long totalSearchNanos = 0L;
            double totalRecall = 0.0;


            queryCount = 100;
            for (int i = 0; i < queryCount; i++) {
                float[] query = queries[i];
                long[] exactNeighbors = groundTruth[i];

                long start = System.nanoTime();

                var searchResponse = service.search(
                        new SearchRequest(query, neighborCount)
                );

                long searchNanos = System.nanoTime() - start;
                totalSearchNanos += searchNanos;

                searchMetrics.add(
                        searchNanos / 1_000_000.0
                );

                List<Long> foundIds = extractIdsFromResponse(searchResponse);

                int matches = 0;
                int returnedCount = Math.min(
                        foundIds.size(),
                        neighborCount
                );

                for (int resultIndex = 0; resultIndex < returnedCount; resultIndex++) {
                    long foundId = foundIds.get(resultIndex);

                    if (containsInTopK(exactNeighbors, foundId, neighborCount)) {
                        matches++;
                    }
                }

                totalRecall += (double) matches / neighborCount;
            }

            double loadMs = (loadEnd - loadStart) / 1_000_000.0;
            double totalSearchMs = totalSearchNanos / 1_000_000.0;
            double qps = queryCount / (totalSearchNanos / 1_000_000_000.0);
            double averageRecall = totalRecall / queryCount;

            System.out.println();
            System.out.println("=== Vector benchmark result ===");
            System.out.println("load_ms: " + loadMs);
            System.out.println("total_search_ms: " + totalSearchMs);
            System.out.println("avg_search_ms: " + searchMetrics.average());
            System.out.println("p95_search_ms: " + searchMetrics.percentile(0.95));
            System.out.println("p99_search_ms: " + searchMetrics.percentile(0.99));
            System.out.println("measured_queries: " + searchMetrics.count());
            System.out.println("qps: " + qps);

            System.out.printf(
                    "Average RECALL@%d: %.2f%%%n",
                    neighborCount,
                    averageRecall * 100.0
            );

            System.out.println("=== Vector benchmark FINISHED ===");
        } catch (Exception e) {
            throw new RuntimeException(
                    "Benchmark failed for dataset: " + hdf5Path,
                    e
            );
        }
    }

    private boolean containsInTopK(
            long[] exactNeighbors,
            long foundId,
            int neighborCount
    ) {
        int limit = Math.min(
                neighborCount,
                exactNeighbors.length
        );

        for (int i = 0; i < limit; i++) {
            long expectedId = exactNeighbors[i] + 1L;

            if (expectedId == foundId) {
                return true;
            }
        }

        return false;
    }

    private List<Long> extractIdsFromResponse(Object searchResponse) {
        Object body = searchResponse;

        if (searchResponse instanceof org.springframework.http.ResponseEntity<?> responseEntity) {
            body = responseEntity.getBody();
        }

        if (!(body instanceof List<?> resultList)) {
            throw new IllegalStateException(
                    "Unexpected search response type: "
                            + (body == null ? "null" : body.getClass().getName())
            );
        }

        List<Long> ids = new ArrayList<>(resultList.size());

        for (Object item : resultList) {
            if (!(item instanceof Neighbor neighbor)) {
                throw new IllegalStateException(
                        "Unexpected search result item: "
                                + (item == null ? "null" : item.getClass().getName())
                );
            }

            ids.add(neighbor.id());
        }

        return ids;
    }
}