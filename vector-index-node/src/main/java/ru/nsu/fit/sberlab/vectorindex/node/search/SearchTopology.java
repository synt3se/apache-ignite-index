package ru.nsu.fit.sberlab.vectorindex.node.search;

import java.util.Map;

/** Назначение запроса: ожидаемые партиции и их primary-владельцы на момент начала запроса. */
public interface SearchTopology {

    Snapshot snapshot();

    /**
     * @param partitions число партиций кэша (ожидаются все {@code 0..partitions-1})
     * @param assignment узел → его primary-партиции по affinity координатора
     */
    record Snapshot(int partitions, Map<String, int[]> assignment, long version) {
    }
}
