package ru.nsu.fit.sberlab.vectorindex.vectorserver.index;

import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchResponse;
import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;
import ru.nsu.fit.sberlab.vectorindex.common.dto.AddRequest;
import ru.nsu.fit.sberlab.vectorindex.common.dto.ClusterStats;
import ru.nsu.fit.sberlab.vectorindex.common.dto.Neighbor;

import java.util.List;
import java.util.Map;

public interface Index {

    void add(long id, AddRequest request);
    boolean delete(long id);
    void clear();
    VectorObject get(long id);
    List<Neighbor> search(float[] vector, int count);

    void save(String path);
    long load(String path);

    void addAll(Map<Long, VectorObject> vectors);
    void rebuild();

    ClusterStats stats();

    default SearchResponse searchFull(float[] vector, int count) {
        throw new UnsupportedOperationException("full search requires aggregator service");
    }
}
