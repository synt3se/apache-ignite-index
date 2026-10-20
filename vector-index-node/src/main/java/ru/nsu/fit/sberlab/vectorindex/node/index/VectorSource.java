package ru.nsu.fit.sberlab.vectorindex.node.index;

import java.util.function.BiConsumer;

/**
 * Источник истины для адаптера: чтение и скан данных кэша, affinity и топология.
 * Ignite-реализация — {@link IgniteVectorSource}; в тестах — карта в памяти.
 */
interface VectorSource {

    /** Primary-партиции локального узла по текущему affinity. */
    int[] ownedPrimaryPartitions();

    boolean isLocalPrimary(int partition);

    int partitionOf(Object key);

    /** Текущий вектор ключа или {@code null}: записи нет или вектор отсутствует. */
    float[] read(Object key);

    /** Локальный скан партиции; записи без вектора не передаются. */
    void scan(int partition, BiConsumer<Object, float[]> consumer);

    long topologyVersion();
}
