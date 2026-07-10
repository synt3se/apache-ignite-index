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
    public void delete(long id) {
        vectors.remove(id);
    }

    @Override
    public List<ScoredVector> search(float[] queryVector, int count) {
        PriorityQueue<ScoredVector> top = new PriorityQueue<>(
                Comparator.comparingDouble(ScoredVector::distance).reversed()
        );

        for (Map.Entry<Long, float[]> entry : vectors.entrySet()) {
            double distance = euclideanDistance(queryVector, entry.getValue());

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

    private double euclideanDistance(float[] a, float[] b) {
        double sum = 0.0;

        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }

        return Math.sqrt(sum);
    }
}