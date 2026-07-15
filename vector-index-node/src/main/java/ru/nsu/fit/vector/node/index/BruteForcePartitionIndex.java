package ru.nsu.fit.vector.node.index;

import ru.nsu.fit.vector.common.ScoredVector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

public class BruteForcePartitionIndex implements PartitionVectorIndex {
    private final Map<Long, float[]> vectors = new ConcurrentHashMap<>();

    @Override
    public void add(long id, float[] vector) {
        vectors.put(id, vector);
    }

    @Override
    public void build(Map<Long, float[]> newVectors) {
        if (newVectors == null) {
            throw new IllegalArgumentException("vectors are required");
        }

        vectors.clear();

        for (Map.Entry<Long, float[]> entry : newVectors.entrySet()) {
            Long id = entry.getKey();
            float[] vector = entry.getValue();

            if (id == null) {
                throw new IllegalArgumentException("vector id is required");
            }

            if (vector == null) {
                throw new IllegalArgumentException("vector is required for id " + id);
            }

            vectors.put(id, vector.clone());
        }
    }

    @Override
    public void delete(long id) {
        vectors.remove(id);
    }

    @Override
    public List<ScoredVector> search(float[] queryVector, int count) {
        PriorityQueue<ScoredVector> top = new PriorityQueue<>(
                Comparator.comparingDouble(ScoredVector::distance).reversed()
        );

        for (Map.Entry<Long, float[]> entry : vectors.entrySet()) {
            double distance = cosineDistance(queryVector, entry.getValue());

            ScoredVector candidate = new ScoredVector(entry.getKey(), distance);

            if (top.size() < count) {
                top.add(candidate);
            } else if (top.peek() != null && distance < top.peek().distance()) {
                top.poll();
                top.add(candidate);
            }
        }

        List<ScoredVector> result = new ArrayList<>(top);
        result.sort(Comparator.comparingDouble(ScoredVector::distance));

        return result;
    }

    @Override
    public void clear() {
        vectors.clear();
    }

    @Override
    public int size() {
        return vectors.size();
    }

    private double cosineDistance(float[] a, float[] b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("vectors are required");
        }

        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "vector dimensions differ: " + a.length + " != " + b.length
            );
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            double av = a[i];
            double bv = b[i];

            dotProduct += av * bv;
            normA += av * av;
            normB += bv * bv;
        }

        if (normA == 0.0 || normB == 0.0) {
            return 1.0;
        }

        double cosineSimilarity =
                dotProduct / Math.sqrt(normA * normB);

        return 1.0 - cosineSimilarity;
    }
}