package ru.nsu.fit.sberlab.vectorindex.vectorserver.index;

import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchResponse;
import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;
import ru.nsu.fit.sberlab.vectorindex.common.dto.AddRequest;
import ru.nsu.fit.sberlab.vectorindex.common.dto.ClusterStats;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexInfo;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexOperationResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.Neighbor;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchRequest;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchResponse;

import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

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

    default ru.nsu.fit.sberlab.vectorindex.common.dto.SearchResponse searchFull(float[] vector, int count) {
        throw new UnsupportedOperationException("full search requires aggregator service");
    }

    /** Поиск по индексу без документов: ключи, расстояния и полный статус. */
    default VectorSearchResponse search(VectorSearchRequest request) {
        throw new UnsupportedOperationException("index search requires aggregator service");
    }

    default List<IndexInfo> indexes() {
        throw new UnsupportedOperationException("index list requires aggregator service");
    }

    /** {@code null} — индекс неизвестен. */
    default IndexStatus status(String indexId) {
        throw new UnsupportedOperationException("index status requires aggregator service");
    }

    /** {@code null} — индекс неизвестен. */
    default IndexOperationResult unload(String indexId) {
        throw new UnsupportedOperationException("index unload requires aggregator service");
    }

    /** {@code null} — индекс неизвестен. */
    default IndexOperationResult rebuild(String indexId) {
        throw new UnsupportedOperationException("index rebuild requires aggregator service");
    }
}
