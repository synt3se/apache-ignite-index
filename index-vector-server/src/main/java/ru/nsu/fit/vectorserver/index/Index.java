package ru.nsu.fit.vectorserver.index;

import ru.nsu.fit.vector.common.VectorObject;
import ru.nsu.fit.vector.common.dto.AddRequest;
import ru.nsu.fit.vector.common.dto.ClusterStats;
import ru.nsu.fit.vector.common.dto.Neighbor;

import java.util.List;

public interface Index {

    void add(long id, AddRequest request);
    boolean delete(long id);
    void clear();
    VectorObject get(long id);
    List<Neighbor> search(float[] vector, int count);

    void save(String path);
    long load(String path);

    ClusterStats stats();

    default ru.nsu.fit.vector.common.dto.SearchResponse searchFull(float[] vector, int count) {
        throw new UnsupportedOperationException("full search requires aggregator service");
    }
}
