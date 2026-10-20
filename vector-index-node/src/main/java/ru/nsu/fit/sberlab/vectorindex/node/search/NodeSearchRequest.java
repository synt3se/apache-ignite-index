package ru.nsu.fit.sberlab.vectorindex.node.search;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Запрос координатора узлу. Бюджет — оставшееся время в наносекундах на момент отправки
 * (абсолютный {@code nanoTime} между машинами не сравним). Обычный класс, а не record:
 * уходит по сети внутри compute job и маршаллируется Ignite (ADR-10).
 */
public final class NodeSearchRequest implements Serializable {
    private static final long serialVersionUID = 2L;

    private String indexId;
    private String fingerprint;
    private float[] query;
    private int k;
    private int[] partitions;
    private long budgetNanos;
    private boolean exact;
    private HashMap<String, Object> params;

    public NodeSearchRequest(NodeQuery query, int[] partitions, long budgetNanos) {
        this.indexId = query.indexId();
        this.fingerprint = query.fingerprint();
        this.query = query.vector();
        this.k = query.k();
        this.partitions = partitions;
        this.budgetNanos = budgetNanos;
        this.exact = query.exact();
        this.params = new HashMap<>(query.params());
    }

    public String indexId() {
        return indexId;
    }

    /** Отпечаток определения на координаторе; узел с другим определением отвечает DEFINITION_MISMATCH. */
    public String fingerprint() {
        return fingerprint;
    }

    public float[] query() {
        return query;
    }

    public int k() {
        return k;
    }

    /** Назначенные узлу партиции; {@code null} — все локальные. */
    public int[] partitions() {
        return partitions;
    }

    public long budgetNanos() {
        return budgetNanos;
    }

    public boolean exact() {
        return exact;
    }

    public Map<String, Object> params() {
        return params == null ? Map.of() : params;
    }
}
