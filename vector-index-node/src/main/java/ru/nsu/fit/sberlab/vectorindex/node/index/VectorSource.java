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

    /** Текущее значение ключа для индекса; {@link SourceValue#ABSENT}, если записи нет. */
    SourceValue read(Object key);

    /** Локальный скан партиции: все записи, решение по каждой принимает адаптер. */
    void scan(int partition, BiConsumer<Object, SourceValue> consumer);

    long topologyVersion();
}
