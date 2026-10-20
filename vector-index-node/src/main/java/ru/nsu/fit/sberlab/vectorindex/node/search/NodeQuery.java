package ru.nsu.fit.sberlab.vectorindex.node.search;

import java.util.Map;
import java.util.Objects;

/**
 * Что ищет координатор: индекс и отпечаток его определения на координаторе (узел с другим
 * определением не участвует в ответе), запрос, {@code k} и параметры алгоритма из запроса.
 */
public record NodeQuery(String indexId, String fingerprint, float[] vector, int k, boolean exact,
                        Map<String, ?> params) {

    public NodeQuery {
        Objects.requireNonNull(indexId, "indexId");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(vector, "vector");
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
