package ru.nsu.fit.sberlab.vectorindex.common.dto;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Поиск по индексу без документов (§7.6).
 *
 * <ul>
 *   <li>{@code params} — параметры конкретного алгоритма; проверяются узлом на совместимость
 *       (JVector: {@code ef ≥ k}; Flat: параметров нет). Неизвестный параметр — ошибка запроса.</li>
 *   <li>{@code allowPartial=false} — неполный ответ возвращается со статусом
 *       {@link SearchStatus#FAILED} и без попаданий.</li>
 *   <li>{@code timeoutMs} — оставшееся время запроса на момент вызова; {@code 0} — значение
 *       сервиса по умолчанию.</li>
 * </ul>
 */
public class VectorSearchRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    public String indexId;
    public float[] vector;
    public int k;
    public boolean exact;
    public Map<String, Object> params = new HashMap<>();
    public boolean allowPartial = true;
    public long timeoutMs;

    public VectorSearchRequest() {
    }

    public VectorSearchRequest(String indexId, float[] vector, int k) {
        this.indexId = indexId;
        this.vector = vector;
        this.k = k;
    }
}
