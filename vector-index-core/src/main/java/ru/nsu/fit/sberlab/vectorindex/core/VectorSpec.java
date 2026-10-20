package ru.nsu.fit.sberlab.vectorindex.core;

import java.util.Objects;

/**
 * Что индексируется: плотный FP32 фиксированной размерности и метрика.
 * Один контракт расстояния для данных, запроса, точного и ANN-поиска.
 */
public record VectorSpec(int dimension, Metric metric) {

    public VectorSpec {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive: " + dimension);
        }
        Objects.requireNonNull(metric, "metric");
    }

    /**
     * Проверяет вектор из источника и возвращает собственную копию ядра
     * (для COSINE — нормализованную). Неверный вектор не исключение, а {@link Ingest.Rejected}:
     * вызывающий обязан исключить запись из проекции и учесть отказ.
     *
     * @throws IllegalArgumentException если {@code vector == null}: отсутствие вектора —
     *                                  решение адаптера (обычно удаление), а не данные
     */
    public Ingest ingest(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("vector is required");
        }
        if (vector.length != dimension) {
            return Ingest.Rejected.WRONG_DIMENSION;
        }
        double squaredNorm = 0;
        for (float x : vector) {
            if (!Float.isFinite(x)) {
                return Ingest.Rejected.NON_FINITE;
            }
            squaredNorm += (double) x * x;
        }
        float[] copy = vector.clone();
        if (metric == Metric.COSINE) {
            normalize(copy, squaredNorm);
        }
        return new Ingest.Accepted(copy);
    }

    /**
     * Готовит вектор запроса: копия, для COSINE нормализованная.
     *
     * @throws IllegalArgumentException для null, неверной размерности, NaN или бесконечности
     */
    public float[] prepareQuery(float[] query) {
        if (query == null) {
            throw new IllegalArgumentException("query vector is required");
        }
        if (query.length != dimension) {
            throw new IllegalArgumentException(
                    "incorrect query dimension: " + query.length + ", required: " + dimension);
        }
        double squaredNorm = 0;
        for (float x : query) {
            if (!Float.isFinite(x)) {
                throw new IllegalArgumentException("query vector must be finite");
            }
            squaredNorm += (double) x * x;
        }
        float[] copy = query.clone();
        if (metric == Metric.COSINE) {
            normalize(copy, squaredNorm);
        }
        return copy;
    }

    /**
     * Расстояние между подготовленным запросом и принятым вектором; меньше — ближе.
     * Оба аргумента должны быть получены через {@link #prepareQuery} и {@link #ingest}.
     */
    public double distance(float[] preparedQuery, float[] stored) {
        if (metric == Metric.COSINE) {
            double dot = 0;
            for (int i = 0; i < dimension; i++) {
                dot += (double) preparedQuery[i] * stored[i];
            }
            return 1.0 - dot;
        }
        double sum = 0;
        for (int i = 0; i < dimension; i++) {
            double d = (double) preparedQuery[i] - stored[i];
            sum += d * d;
        }
        return sum;
    }

    private static void normalize(float[] vector, double squaredNorm) {
        if (squaredNorm == 0) {
            return; // нулевая норма остаётся нулём: расстояние 1.0 до любого вектора
        }
        double inverse = 1.0 / Math.sqrt(squaredNorm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] * inverse);
        }
    }
}
