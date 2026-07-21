package ru.nsu.fit.vector.node.index;

import ru.nsu.fit.vector.common.ScoredVector;

import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

public interface PartitionVectorIndex {
    void add(long id, float[] vector);

    void build(Map<Long, float[]> vectors);
    void delete(long id);
    List<ScoredVector> search(float[] queryVector, int count, LongPredicate filter);
    default void seedAndBuildAsync(Map<Long, float[]> vectors) { build(vectors); }
    default long dedupSkippedCount() { return 0; }
    default int pendingCount() { return 0; }
    void clear();
    int size();
}