package ru.nsu.fit.sberlab.vectorindex.vectorserver.rest;

import java.util.List;

import ru.nsu.fit.sberlab.vectorindex.common.dto.Incompleteness;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexHit;
import ru.nsu.fit.sberlab.vectorindex.common.dto.KeyType;
import ru.nsu.fit.sberlab.vectorindex.common.dto.PartitionFailure;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchError;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchResponse;

/**
 * JSON-представление ответа поиска по индексу. Ключ — с явным типом: JSON не различает строку
 * и UUID, а число может потерять тип ({@code LONG} пишется числом, {@code STRING} и
 * {@code UUID} — строкой).
 */
public record IndexSearchView(String indexId, SearchStatus status, SearchError error, String message,
                              List<Incompleteness> incomplete, List<Hit> hits, int expectedPartitions,
                              int[] searchedPartitions, int[] knownGapPartitions,
                              List<PartitionFailure> failedPartitions, boolean timedOut, int respondedNodes,
                              int totalNodes, int duplicateKeys, long topologyVersion, long tookMs) {

    public record Hit(Object key, KeyType keyType, double distance) {
        static Hit of(IndexHit hit) {
            KeyType type = hit.keyType();
            Object key = type == KeyType.UUID ? hit.key.toString() : hit.key;
            return new Hit(key, type, hit.distance);
        }
    }

    public static IndexSearchView of(VectorSearchResponse r) {
        List<Hit> hits = r.hits == null ? List.of() : r.hits.stream().map(Hit::of).toList();
        return new IndexSearchView(r.indexId, r.status, r.error, r.message,
                r.incomplete == null ? List.of() : r.incomplete, hits, r.expectedPartitions,
                r.searchedPartitions, r.knownGapPartitions, r.failedPartitions, r.timedOut, r.respondedNodes,
                r.totalNodes, r.duplicateKeys, r.topologyVersion, r.tookMs);
    }
}
