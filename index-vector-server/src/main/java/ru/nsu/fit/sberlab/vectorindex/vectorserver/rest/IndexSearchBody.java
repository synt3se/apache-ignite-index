package ru.nsu.fit.sberlab.vectorindex.vectorserver.rest;

import java.util.HashMap;
import java.util.Map;

import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchRequest;

/**
 * Тело {@code POST /indexes/{id}/search}. Размерность, {@code k} и параметры алгоритма
 * проверяет координатор по определению индекса (ошибка — 400).
 *
 * @param params      параметры алгоритма, например {@code {"ef": 128}} для JVector
 * @param allowPartial {@code false} — неполный ответ возвращается как 503 без попаданий
 * @param timeoutMs   бюджет запроса на сервере; {@code null} — {@code vector.search.timeout-ms}
 */
public record IndexSearchBody(float[] vector, Integer k, Boolean exact, Map<String, Object> params,
                              Boolean allowPartial, Long timeoutMs) {

    VectorSearchRequest toRequest(String indexId) {
        VectorSearchRequest request = new VectorSearchRequest(indexId, vector, k == null ? 0 : k);
        request.exact = Boolean.TRUE.equals(exact);
        request.params = params == null ? new HashMap<>() : new HashMap<>(params);
        request.allowPartial = allowPartial == null || allowPartial;
        request.timeoutMs = timeoutMs == null ? 0 : timeoutMs;
        return request;
    }
}
