package ru.nsu.fit.sberlab.vectorindex.vectorserver.index;

import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchResponse;
import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;
import ru.nsu.fit.sberlab.vectorindex.common.dto.AddRequest;
import ru.nsu.fit.sberlab.vectorindex.common.dto.ClusterStats;
import ru.nsu.fit.sberlab.vectorindex.common.dto.Neighbor;
import ru.nsu.fit.sberlab.vectorindex.common.filter.VectorMetadataFilter;

import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

public interface Index {

    void add(long id, AddRequest request);
    boolean delete(long id);
    void clear();
    VectorObject get(long id);
    List<Neighbor> search(float[] vector, int count, String filter);

    void save(String path);
    long load(String path);

    void addAll(Map<Long, VectorObject> vectors);
    void rebuild();

    ClusterStats stats();

    default ru.nsu.fit.sberlab.vectorindex.common.dto.SearchResponse searchFull(float[] vector, int count, String filter) {
        throw new UnsupportedOperationException("full search requires aggregator service");
    }
}
