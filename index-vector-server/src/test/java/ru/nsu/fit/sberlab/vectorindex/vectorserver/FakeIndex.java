package ru.nsu.fit.sberlab.vectorindex.vectorserver;

import java.util.List;
import java.util.Map;

import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;
import ru.nsu.fit.sberlab.vectorindex.common.dto.AddRequest;
import ru.nsu.fit.sberlab.vectorindex.common.dto.ClusterStats;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexBarrier;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexOperationResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.Neighbor;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchRequest;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchResponse;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.index.Index;

/** Подмена {@link Index} для тестов REST и метрик: заданные ответы, остальное не поддерживается. */
public class FakeIndex implements Index {
    public VectorSearchResponse response;
    public VectorSearchRequest lastRequest;
    public IndexOperationResult operation;
    public IndexStatus status;
    public ClusterStats stats;

    @Override public VectorSearchResponse search(VectorSearchRequest request) {
        lastRequest = request;
        return response;
    }
    @Override public IndexOperationResult unload(String indexId) { return operation; }
    @Override public IndexOperationResult rebuild(String indexId) { return operation; }
    @Override public IndexStatus status(String indexId) { return status; }
    @Override public ClusterStats stats() { return stats; }
    @Override public IndexOperationResult truncate(String indexId) { return operation; }
    @Override public IndexBarrier beginBarrier(String indexId, boolean optimize) { return barrier; }
    @Override public IndexBarrier barrierProgress(String indexId, String barrierId) { return barrier; }

    public IndexBarrier barrier;

    @Override public void add(long id, AddRequest request) { throw new UnsupportedOperationException(); }
    @Override public boolean delete(long id) { throw new UnsupportedOperationException(); }
    @Override public void clear() { throw new UnsupportedOperationException(); }
    @Override public VectorObject get(long id) { throw new UnsupportedOperationException(); }
    @Override public List<Neighbor> search(float[] vector, int count) { throw new UnsupportedOperationException(); }
    @Override public void save(String path) { throw new UnsupportedOperationException(); }
    @Override public long load(String path) { throw new UnsupportedOperationException(); }
    @Override public void addAll(Map<Long, VectorObject> vectors) { throw new UnsupportedOperationException(); }
    @Override public void rebuild() { throw new UnsupportedOperationException(); }
}
