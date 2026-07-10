package ru.nsu.fit.vector.node.index;

import ru.nsu.fit.vector.common.ScoredVector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class LocalTest {
    private static final int DIMENSION = 128;
    private static final int QUERY_COUNT = 100;
    private static final int TOP_K = 10;
    private static final int WARMUP_QUERY_COUNT = 20;
    private static final long RANDOM_SEED = 42L;

    private static final int[] VECTOR_COUNTS = {
            10_000,
            50_000,
            100_000,
            300_000
    };

    public static void main(String[] args) {
        for (int vectorCount : VECTOR_COUNTS) {
            runBenchmark(vectorCount);
            System.gc();
        }
    }

    private static void runBenchmark(int vectorCount) {
        System.out.println();
        System.out.println("========================================");
        System.out.println("VECTOR COUNT: " + vectorCount);
        System.out.println("========================================");

        Random random = new Random(RANDOM_SEED + vectorCount);

        List<float[]> vectors = new ArrayList<>(vectorCount);

        System.out.println("Generating vectors...");

        long generationStart = System.nanoTime();

        for (int i = 0; i < vectorCount; i++) {
            vectors.add(randomNormalizedVector(random, DIMENSION));
        }

        long generationTime = System.nanoTime() - generationStart;

        PartitionVectorIndex bruteForceIndex =
                new BruteForcePartitionIndex();

        PartitionVectorIndex jVectorIndex =
                new JVectorPartitionIndex(
                        DIMENSION
                );

        System.out.println("Building brute force index...");

        long bruteForceBuildStart = System.nanoTime();

        for (int i = 0; i < vectorCount; i++) {
            bruteForceIndex.add(i, vectors.get(i));
        }

        long bruteForceBuildTime =
                System.nanoTime() - bruteForceBuildStart;

        System.out.println("Building JVector index...");

        long jVectorBuildStart = System.nanoTime();

        for (int i = 0; i < vectorCount; i++) {
            jVectorIndex.add(i, vectors.get(i));
        }

        long jVectorBuildTime =
                System.nanoTime() - jVectorBuildStart;

        List<float[]> queries =
                createQueries(vectors, random, QUERY_COUNT);

        System.out.println("Warming up...");

        warmUp(
                bruteForceIndex,
                jVectorIndex,
                queries
        );

        System.out.println("Running benchmark...");

        long totalBruteForceSearchNanos = 0L;
        long totalJVectorSearchNanos = 0L;

        long minBruteForceSearchNanos = Long.MAX_VALUE;
        long maxBruteForceSearchNanos = Long.MIN_VALUE;

        long minJVectorSearchNanos = Long.MAX_VALUE;
        long maxJVectorSearchNanos = Long.MIN_VALUE;

        double totalRecall = 0.0;
        int sameFirstResultCount = 0;

        for (int queryIndex = 0;
             queryIndex < QUERY_COUNT;
             queryIndex++) {

            float[] query = queries.get(queryIndex);

            long bruteForceStart = System.nanoTime();

            List<ScoredVector> expected =
                    bruteForceIndex.search(query, TOP_K);

            long bruteForceSearchNanos =
                    System.nanoTime() - bruteForceStart;

            long jVectorStart = System.nanoTime();

            List<ScoredVector> actual =
                    jVectorIndex.search(query, TOP_K);

            long jVectorSearchNanos =
                    System.nanoTime() - jVectorStart;

            totalBruteForceSearchNanos +=
                    bruteForceSearchNanos;

            totalJVectorSearchNanos +=
                    jVectorSearchNanos;

            minBruteForceSearchNanos = Math.min(
                    minBruteForceSearchNanos,
                    bruteForceSearchNanos
            );

            maxBruteForceSearchNanos = Math.max(
                    maxBruteForceSearchNanos,
                    bruteForceSearchNanos
            );

            minJVectorSearchNanos = Math.min(
                    minJVectorSearchNanos,
                    jVectorSearchNanos
            );

            maxJVectorSearchNanos = Math.max(
                    maxJVectorSearchNanos,
                    jVectorSearchNanos
            );

            double recall = recallAtK(expected, actual);
            totalRecall += recall;

            if (!expected.isEmpty()
                    && !actual.isEmpty()
                    && expected.get(0).id() == actual.get(0).id()) {
                sameFirstResultCount++;
            }

            if ((queryIndex + 1) % 10 == 0) {
                System.out.printf(
                        "Queries completed: %d/%d, current recall@%d: %.3f%n",
                        queryIndex + 1,
                        QUERY_COUNT,
                        TOP_K,
                        recall
                );
            }
        }

        double averageBruteForceSearchMicros =
                nanosToMicros(totalBruteForceSearchNanos)
                        / QUERY_COUNT;

        double averageJVectorSearchMicros =
                nanosToMicros(totalJVectorSearchNanos)
                        / QUERY_COUNT;

        double averageRecall =
                totalRecall / QUERY_COUNT;

        double speedup =
                averageBruteForceSearchMicros
                        / averageJVectorSearchMicros;

        System.out.println();
        System.out.println("=== SUMMARY ===");

        System.out.printf(
                "Vectors: %,d%n",
                vectorCount
        );

        System.out.printf(
                "Dimension: %d%n",
                DIMENSION
        );

        System.out.printf(
                "Queries: %d%n",
                QUERY_COUNT
        );

        System.out.printf(
                "Top K: %d%n",
                TOP_K
        );

        System.out.printf(
                "Vector generation time: %.3f ms%n",
                nanosToMillis(generationTime)
        );

        System.out.printf(
                "Brute force build time: %.3f ms%n",
                nanosToMillis(bruteForceBuildTime)
        );

        System.out.printf(
                "JVector build time: %.3f ms%n",
                nanosToMillis(jVectorBuildTime)
        );

        System.out.printf(
                "Average brute force search: %.3f µs%n",
                averageBruteForceSearchMicros
        );

        System.out.printf(
                "Min brute force search: %.3f µs%n",
                nanosToMicros(minBruteForceSearchNanos)
        );

        System.out.printf(
                "Max brute force search: %.3f µs%n",
                nanosToMicros(maxBruteForceSearchNanos)
        );

        System.out.printf(
                "Average JVector search: %.3f µs%n",
                averageJVectorSearchMicros
        );

        System.out.printf(
                "Min JVector search: %.3f µs%n",
                nanosToMicros(minJVectorSearchNanos)
        );

        System.out.printf(
                "Max JVector search: %.3f µs%n",
                nanosToMicros(maxJVectorSearchNanos)
        );

        System.out.printf(
                "Average recall@%d: %.3f%n",
                TOP_K,
                averageRecall
        );

        System.out.printf(
                "Same first result: %d/%d%n",
                sameFirstResultCount,
                QUERY_COUNT
        );

        System.out.printf(
                "Search speedup: %.2fx%n",
                speedup
        );

        System.out.printf(
                "Brute force QPS: %.2f%n",
                1_000_000.0 / averageBruteForceSearchMicros
        );

        System.out.printf(
                "JVector QPS: %.2f%n",
                1_000_000.0 / averageJVectorSearchMicros
        );

        closeIndex(jVectorIndex);
        closeIndex(bruteForceIndex);
    }

    private static List<float[]> createQueries(
            List<float[]> vectors,
            Random random,
            int queryCount
    ) {
        List<float[]> queries =
                new ArrayList<>(queryCount);

        for (int i = 0; i < queryCount; i++) {
            int sourceIndex =
                    random.nextInt(vectors.size());

            queries.add(
                    noisyCopy(
                            vectors.get(sourceIndex),
                            random,
                            0.03f
                    )
            );
        }

        return queries;
    }

    private static void warmUp(
            PartitionVectorIndex bruteForceIndex,
            PartitionVectorIndex jVectorIndex,
            List<float[]> queries
    ) {
        int warmupCount = Math.min(
                WARMUP_QUERY_COUNT,
                queries.size()
        );

        for (int i = 0; i < warmupCount; i++) {
            float[] query = queries.get(i);

            bruteForceIndex.search(query, TOP_K);
            jVectorIndex.search(query, TOP_K);
        }
    }

    private static float[] randomNormalizedVector(
            Random random,
            int dimension
    ) {
        float[] vector = new float[dimension];

        double normSquared = 0.0;

        for (int i = 0; i < dimension; i++) {
            float value =
                    (float) random.nextGaussian();

            vector[i] = value;
            normSquared += value * value;
        }

        normalize(vector, normSquared);

        return vector;
    }

    private static float[] noisyCopy(
            float[] source,
            Random random,
            float noiseStrength
    ) {
        float[] result = source.clone();

        double normSquared = 0.0;

        for (int i = 0; i < result.length; i++) {
            result[i] +=
                    (float) random.nextGaussian()
                            * noiseStrength;

            normSquared += result[i] * result[i];
        }

        normalize(result, normSquared);

        return result;
    }

    private static void normalize(
            float[] vector,
            double normSquared
    ) {
        if (normSquared == 0.0) {
            throw new IllegalArgumentException(
                    "Cannot normalize zero vector"
            );
        }

        double norm = Math.sqrt(normSquared);

        for (int i = 0; i < vector.length; i++) {
            vector[i] /= (float) norm;
        }
    }

    private static double recallAtK(
            List<ScoredVector> expected,
            List<ScoredVector> actual
    ) {
        if (expected.isEmpty()) {
            return actual.isEmpty() ? 1.0 : 0.0;
        }

        Set<Long> expectedIds = new HashSet<>();

        for (ScoredVector result : expected) {
            expectedIds.add(result.id());
        }

        int matches = 0;

        for (ScoredVector result : actual) {
            if (expectedIds.contains(result.id())) {
                matches++;
            }
        }

        return (double) matches / expected.size();
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static double nanosToMicros(long nanos) {
        return nanos / 1_000.0;
    }

    private static void closeIndex(
            PartitionVectorIndex index
    ) {
        if (!(index instanceof AutoCloseable closeable)) {
            return;
        }

        try {
            closeable.close();
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to close index",
                    e
            );
        }
    }
}