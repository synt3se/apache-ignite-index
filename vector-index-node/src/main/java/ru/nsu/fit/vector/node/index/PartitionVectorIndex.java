package ru.nsu.fit.vector.node.index;

import ru.nsu.fit.vector.common.ScoredVector;

import java.util.List;
import java.util.Map;

public interface PartitionVectorIndex {
    void add(long id, float[] vector);

    void build(Map<Long, float[]> vectors);
    void delete(long id);
    List<ScoredVector> search(float[] queryVector, int count);
    default void seedAndBuildAsync(Map<Long, float[]> vectors) {
        build(vectors);
    }
    default int pendingCount() { return 0; }
    void clear();
    int size();
}