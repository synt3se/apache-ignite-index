package ru.nsu.fit.vectorserver.benchmark;

import ru.nsu.fit.vectorserver.core.Index;
import ru.nsu.fit.vector.common.dto.AddRequest;

import java.util.Random;



public class BenchmarkRunner{
    private final Index index;

    public BenchmarkRunner(Index index){
        this.index = index;
    }

    public void run(
            int vectorCount,
            int queryCount,
            int dimension,
            int neighborCount
    ){
        System.out.println("=== Brute force benchmark STARTED ===");
        System.out.println("vectors: " + vectorCount);
        System.out.println("queries: " + queryCount);
        System.out.println("dimension: " + dimension);
        System.out.println("neighbors searchCount: " + neighborCount);

        Random random = new Random(42);

        index.clear();

        float[][] train = new float[vectorCount][];
        for (int i = 0; i < vectorCount; i++) {
            train[i] = randomVector(random, dimension);
        }

        long loadStart = System.nanoTime();

        for (int i = 0; i < vectorCount; i++) {
            AddRequest request = new AddRequest(
                    train[i],
                    "benchmark://vector/" + i,
                    "Source: benchmark"
            );

            index.add(i, request); //TODO wtf
        }

        long loadEnd = System.nanoTime();

        float[][] queries = new float[queryCount][];

        for (int i = 0; i < queryCount; i++) {
            queries[i] = randomVector(random, dimension);
        }

        BenchmarkMetrics searchMetrics = new BenchmarkMetrics();


        int warmupQueries = Math.min(10, queryCount);;
        for (int i = 0; i < warmupQueries; i++) {
            index.search(queries[i], neighborCount);
        }


        long searchStartTotal = System.nanoTime();

        for (float[] query : queries) {
            long start = System.nanoTime();

            index.search(query, neighborCount);

            long end = System.nanoTime();

            double searchMs = (end - start) / 1_000_000.0;
            searchMetrics.add(searchMs);
        }

        long searchEndTotal = System.nanoTime();

        double loadMs = (loadEnd - loadStart) / 1_000_000.0;
        double totalSearchMs = (searchEndTotal - searchStartTotal) / 1_000_000.0;
        double qps = queryCount / (totalSearchMs / 1000.0);

        System.out.println();
        System.out.println("=== Brute force benchmark result ===");
        System.out.println("load_ms: " + loadMs);
        System.out.println("total_search_ms: " + totalSearchMs);
        System.out.println("avg_search_ms: " + searchMetrics.average());
        System.out.println("p95_search_ms: " + searchMetrics.percentile(0.95));
        System.out.println("p99_search_ms: " + searchMetrics.percentile(0.99));
        System.out.println("measured_queries: " + searchMetrics.count());
        System.out.println("qps: " + qps); //queries per second
        System.out.println("=== Brute force benchmark FINISHED ===");
    }

    private float[] randomVector(Random random, int dimension) {
        float[] vector = new float[dimension];

        for (int i = 0; i < dimension; i++) {
            vector[i] = random.nextFloat();
        }

        return vector;
    }
}
