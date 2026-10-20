package ru.nsu.fit.sberlab.vectorindex.common.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Ответ поиска по индексу: ключи и расстояния по возрастанию, без документов, и полный статус.
 * Покрытие — множество партиций, реально обработанных назначенными владельцами.
 */
public class VectorSearchResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    public String indexId;
    public SearchStatus status;
    /** Ошибка запроса; {@code null} — запрос выполнен (статус описывает полноту). */
    public SearchError error;
    public String message;
    public List<Incompleteness> incomplete = new ArrayList<>();
    public List<IndexHit> hits = new ArrayList<>();
    public int expectedPartitions;
    public int[] searchedPartitions = new int[0];
    public int[] knownGapPartitions = new int[0];
    public List<PartitionFailure> failedPartitions = new ArrayList<>();
    public boolean timedOut;
    public int respondedNodes;
    public int totalNodes;
    public int duplicateKeys;
    public long topologyVersion;
    public long tookMs;

    public VectorSearchResponse() {
    }

    public boolean partial() {
        return status != SearchStatus.COMPLETE;
    }

    public static VectorSearchResponse failed(String indexId, SearchError error, String message) {
        VectorSearchResponse response = new VectorSearchResponse();
        response.indexId = indexId;
        response.status = SearchStatus.FAILED;
        response.error = error;
        response.message = message;
        return response;
    }
}
