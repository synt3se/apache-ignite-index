package ru.nsu.fit.sberlab.vectorindex.node.search;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import ru.nsu.fit.sberlab.vectorindex.common.dto.Incompleteness;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexHit;
import ru.nsu.fit.sberlab.vectorindex.common.dto.PartitionFailure;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchResponse;

/**
 * Результат распределённого поиска без документов: ключи и расстояния по возрастанию
 * (все кандидаты для добора при гидрации) и полный статус выполнения.
 */
public record DistributedSearchResult(List<IndexHit> hits, int expectedPartitions, int[] searchedPartitions,
                                      List<PartitionFailure> failedPartitions, int[] knownGapPartitions,
                                      boolean timedOut, int respondedNodes, int totalNodes, int duplicateKeys,
                                      long topologyVersion) {

    /** Все причины неполноты; проверяется каждая, а не только число обработанных партиций. */
    public Set<Incompleteness> incompleteness() {
        Set<Incompleteness> reasons = EnumSet.noneOf(Incompleteness.class);
        if (searchedPartitions.length < expectedPartitions || !failedPartitions.isEmpty()) {
            reasons.add(Incompleteness.MISSING_PARTITIONS);
        }
        if (knownGapPartitions.length > 0) {
            reasons.add(Incompleteness.KNOWN_GAP);
        }
        if (timedOut) {
            reasons.add(Incompleteness.TIMED_OUT);
        }
        if (respondedNodes < totalNodes) {
            reasons.add(Incompleteness.NODE_NO_RESPONSE);
        }
        if (duplicateKeys > 0) {
            reasons.add(Incompleteness.DUPLICATE_KEYS);
        }
        return reasons;
    }

    public boolean partial() {
        return !incompleteness().isEmpty();
    }

    /**
     * Ответ клиенту: top-k и статус. При {@code allowPartial=false} неполный ответ —
     * {@link SearchStatus#FAILED} без попаданий, покрытие и причины сохраняются.
     */
    public VectorSearchResponse toResponse(String indexId, int k, boolean allowPartial, long tookMs) {
        VectorSearchResponse response = new VectorSearchResponse();
        response.indexId = indexId;
        Set<Incompleteness> reasons = incompleteness();
        response.incomplete = new ArrayList<>(reasons);
        if (reasons.isEmpty()) {
            response.status = SearchStatus.COMPLETE;
        } else {
            response.status = allowPartial ? SearchStatus.PARTIAL : SearchStatus.FAILED;
        }
        if (response.status != SearchStatus.FAILED) {
            response.hits = new ArrayList<>(hits.subList(0, Math.min(Math.max(k, 0), hits.size())));
        }
        response.expectedPartitions = expectedPartitions;
        response.searchedPartitions = searchedPartitions.clone();
        response.knownGapPartitions = knownGapPartitions.clone();
        response.failedPartitions = new ArrayList<>(failedPartitions);
        response.timedOut = timedOut;
        response.respondedNodes = respondedNodes;
        response.totalNodes = totalNodes;
        response.duplicateKeys = duplicateKeys;
        response.topologyVersion = topologyVersion;
        response.tookMs = tookMs;
        return response;
    }
}
