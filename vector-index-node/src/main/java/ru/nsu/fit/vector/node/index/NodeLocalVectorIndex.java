package ru.nsu.fit.vector.node.index;

import org.apache.ignite.Ignite;
import ru.nsu.fit.vector.common.ScoredVector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NodeLocalVectorIndex {
    private final Ignite ignite;
    private final String cacheName;

    private final Map<Integer, PartitionVectorIndex> indexesByPartition =
            new ConcurrentHashMap<>();

    public NodeLocalVectorIndex(Ignite ignite, String cacheName) {
        this.ignite = ignite;
        this.cacheName = cacheName;
    }

    public void addLocal(long id, float[] vector) {
        int partition = ignite.affinity(cacheName).partition(id);

        PartitionVectorIndex index = indexesByPartition.computeIfAbsent(
                partition,
                ignored -> new BruteForcePartitionIndex()
        ); //TODO реализация находится здесь, собственно сюда подставляется HNSW
        index.add(id, vector);
    }

    public void deleteLocal(long id) {
        int partition = ignite.affinity(cacheName).partition(id);

        PartitionVectorIndex index = indexesByPartition.get(partition);

        if (index != null) {
            index.delete(id);
        }
    }

    public List<ScoredVector> searchLocal(float[] queryVector, int count) {
        PriorityQueue<ScoredVector> top = new PriorityQueue<>(
                Comparator.comparingDouble(ScoredVector::distance).reversed()
        );

        for (PartitionVectorIndex index : indexesByPartition.values()) {
            List<ScoredVector> localResult = index.search(queryVector, count);

            for (ScoredVector candidate : localResult) {
                if (top.size() < count) {
                    top.add(candidate);
                } else if (top.peek() != null && candidate.distance() < top.peek().distance()) {
                    top.poll();
                    top.add(candidate);
                }
            }
        }

        List<ScoredVector> result = new ArrayList<>(top);
        result.sort(Comparator.comparingDouble(ScoredVector::distance));

        return result;
    }

    public void clearLocal() {
        indexesByPartition.values().forEach(PartitionVectorIndex::clear);
        indexesByPartition.clear();
    }
}