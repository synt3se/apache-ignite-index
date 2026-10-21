package ru.nsu.fit.sberlab.vectorindex.vectorserver.search;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexInfo;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchError;

/**
 * Пакетная запись векторов с внешними ID в кэш индекса (загрузка бенчмарка). Весь батч
 * проверяется до записи: при любой ошибке не записывается ничего. Значение кэша — сам
 * {@code float[]}, ключ — {@code Long}: такую загрузку принимает только индекс без поля
 * (значение кэша — вектор); индекс по полю {@code BinaryObject} с ней несовместим. Повтор батча с
 * теми же ID и векторами безопасен: запись идемпотентна, а индекс сворачивает неизменившиеся
 * значения.
 */
public final class VectorUploader {

    public static final int MAX_BATCH = 10_000;

    /** Запись подтверждённого батча в кэш (тонкий клиент: {@code putAll}). */
    @FunctionalInterface
    public interface Writer {
        void putAll(String cacheName, Map<Long, float[]> batch);
    }

    private final Function<String, IndexInfo> catalog;
    private final Writer writer;

    /** @param catalog определение индекса по {@code indexId} или {@code null}, если индекс неизвестен */
    public VectorUploader(Function<String, IndexInfo> catalog, Writer writer) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /**
     * @return число записанных векторов
     * @throws IndexRequestException неизвестный индекс, индекс по полю, неверный батч
     */
    public int upload(String indexId, long[] ids, float[][] vectors) {
        IndexInfo index = catalog.apply(indexId);
        if (index == null) {
            throw new IndexRequestException(SearchError.UNKNOWN_INDEX, "unknown index: " + indexId);
        }
        if (index.field != null) {
            throw invalid("index '" + indexId + "' reads field '" + index.field + "' of BinaryObject values; "
                    + "batch upload writes plain float[] values: configure an index without a field on its own cache");
        }
        if (ids == null || vectors == null || ids.length != vectors.length) {
            throw invalid("ids and vectors must be present and of the same length");
        }
        if (ids.length > MAX_BATCH) {
            throw invalid("batch of " + ids.length + " exceeds " + MAX_BATCH);
        }
        Set<Long> seen = new HashSet<>();
        Map<Long, float[]> batch = new LinkedHashMap<>();
        for (int i = 0; i < ids.length; i++) {
            if (!seen.add(ids[i])) {
                throw invalid("duplicate id " + ids[i] + " in the batch");
            }
            float[] vector = vectors[i];
            if (vector == null || vector.length != index.dimension) {
                throw invalid("vector " + i + " (id " + ids[i] + "): dimension "
                        + (vector == null ? "null" : vector.length) + ", required " + index.dimension);
            }
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw invalid("vector " + i + " (id " + ids[i] + ") contains " + value);
                }
            }
            batch.put(ids[i], vector);
        }
        if (!batch.isEmpty()) {
            writer.putAll(index.cacheName, batch);
        }
        return batch.size();
    }

    private static IndexRequestException invalid(String message) {
        return new IndexRequestException(SearchError.INVALID_REQUEST, message);
    }
}
