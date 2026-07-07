package ru.nsu.fit.vector.node.index;

import ru.nsu.fit.vector.common.ScoredVector;

import java.util.List;

public interface PartitionVectorIndex {
    void add(long id, float[] vector);

    void delete(long id);

    List<ScoredVector> search(float[] queryVector, int count);

    void clear();
}