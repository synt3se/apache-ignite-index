package ru.nsu.fit.sberlab.vectorindex.node.search;

import java.io.Serializable;

/**
 * Запрос узлу: только назначенные ему партиции и остаток бюджета (относительный —
 * абсолютный {@code nanoTime} между машинами не сравним). Обычный класс, а не record:
 * объект уходит по сети внутри compute job и маршаллируется Ignite.
 */
public final class NodeSearchRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private float[] query;
    private int k;
    private int[] partitions;
    private long budgetNanos;

    public NodeSearchRequest(float[] query, int k, int[] partitions, long budgetNanos) {
        this.query = query;
        this.k = k;
        this.partitions = partitions;
        this.budgetNanos = budgetNanos;
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
}
