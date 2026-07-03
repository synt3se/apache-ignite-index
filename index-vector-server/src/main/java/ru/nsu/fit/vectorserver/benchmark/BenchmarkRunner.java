package ru.nsu.fit.vectorserver.benchmark;

import io.jhdf.HdfFile;
import io.jhdf.api.Dataset;
import ru.nsu.fit.vectorserver.VectorService;
import ru.nsu.fit.vectorserver.dto.AddRequest;
import ru.nsu.fit.vectorserver.dto.Neighbor;
import ru.nsu.fit.vectorserver.dto.SearchRequest;

import java.io.File;
import java.util.ArrayList;
import java.util.List;


public class BenchmarkRunner{
    private final VectorService service;

    public BenchmarkRunner(VectorService service){
        this.service = service;
    }

    public void run(
            int neighborCount,
            String hdf5Path
    ){
        File file = new File(hdf5Path);
        if (!file.exists()) {
            throw new IllegalArgumentException("Incorrect dataset filepath: " + hdf5Path);
        }
        try (HdfFile hdfFile = new HdfFile(file)) {
            Dataset trainDataset = hdfFile.getDatasetByPath("train");
            int[] trainDims = trainDataset.getDimensions();
            int vectorCount = trainDims[0];
            int dimension = trainDims[1];

            Dataset testDataset = hdfFile.getDatasetByPath("test");
            int[] testDims = testDataset.getDimensions();
            int queryCount = testDims[0];
            int maxNeighbors = hdfFile.getDatasetByPath("neighbors").getDimensions()[1];
            neighborCount = Math.min(neighborCount, maxNeighbors);

            System.out.println("=== Brute force benchmark STARTED ===");
            System.out.println("Dataset: " + file.getName());
            System.out.println("vectors: " + vectorCount);
            System.out.println("queries: " + queryCount);
            System.out.println("dimension: " + dimension);
            System.out.println("neighbors count: " + neighborCount);

            service.clear();

            long loadStart = System.nanoTime();

            int batchSize = 10000;
            for (int startIdx = 0; startIdx < vectorCount; startIdx += batchSize) {
                int currentBatchSize = Math.min(batchSize, vectorCount - startIdx);
                if (startIdx % 1000 ==0){
                    System.out.println("(～￣▽￣)～ " + startIdx);
                }
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
            int[][] groundTruth = (int[][]) hdfFile.getDatasetByPath("neighbors").getData();

            BenchmarkMetrics searchMetrics = new BenchmarkMetrics();

            int warmupQueries = Math.min(10, queryCount);
            for (int i = 0; i < warmupQueries; i++) {
                service.search(new SearchRequest(queries[i], neighborCount));
            }

            long searchStartTotal = System.nanoTime();
            double totalRecall = 0.0;

            for (int i = 0; i < queryCount; i++) {
                float[] query = queries[i];
                int[] exactNeighbors = groundTruth[i];

                long start = System.nanoTime();
                var searchResponse = service.search(new SearchRequest(query, neighborCount));
                long end = System.nanoTime();

                int matches = 0;
                List<Integer> foundIndices = extractIndicesFromResponse(searchResponse);
                for (int foundIdx : foundIndices) {
                    if (contains(exactNeighbors, foundIdx)) {
                        matches++;
                    }
                }
                totalRecall += (double) matches / neighborCount;

                double searchMs = (end - start) / 1_000_000.0;
                searchMetrics.add(searchMs);
            }

            long searchEndTotal = System.nanoTime();

            double loadMs = (loadEnd - loadStart) / 1_000_000.0;
            double totalSearchMs = (searchEndTotal - searchStartTotal) / 1_000_000.0;
            double qps = queryCount / (totalSearchMs / 1000.0);
            double avgRecall = (totalRecall / queryCount) * 100.0;

            System.out.println();
            System.out.println("=== Brute force benchmark result ===");
            System.out.println("load_ms: " + loadMs);
            System.out.println("total_search_ms: " + totalSearchMs);
            System.out.println("avg_search_ms: " + searchMetrics.average());
            System.out.println("p95_search_ms: " + searchMetrics.percentile(0.95));
            System.out.println("p99_search_ms: " + searchMetrics.percentile(0.99));
            System.out.println("measured_queries: " + searchMetrics.count());
            System.out.println("qps: " + qps);
            System.out.printf("Average RECALL: %.2f%%\n", avgRecall);
            System.out.println("=== Brute force benchmark FINISHED ===");
        } catch (Exception e) {
            e.printStackTrace(); //TODO
        }
    }
    private boolean contains(int[] array, int value) {
        for (int i : array) {
            if (i + 1 == value) return true; // В файлах тестов id начинаются с 0
        }
        return false;
    }

    private List<Integer> extractIndicesFromResponse(Object searchResponse) {
        List<Integer> indices = new ArrayList<>();

        Object body = searchResponse;
        if (searchResponse instanceof org.springframework.http.ResponseEntity<?>) {
            body = ((org.springframework.http.ResponseEntity<?>) searchResponse).getBody();
        }

        if (body instanceof List<?>) {
            List<?> list = (List<?>) body;
            for (Object item : list) {
                if (item instanceof Neighbor) {
                    Neighbor neighbor = (Neighbor) item;
                    indices.add((int) neighbor.id());
                }
            }
        }

        return indices;
    }
}
