package ru.nsu.fit.sberlab.vectorindex.node.search;

import java.io.Serializable;

/**
 * Запрос узлу: только назначенные ему партиции и остаток бюджета (относительный —
 * абсолютный {@code nanoTime} между машинами не сравним).
 */
public record NodeSearchRequest(float[] query, int k, int[] partitions, long budgetNanos) implements Serializable {
}
