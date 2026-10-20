package ru.nsu.fit.sberlab.vectorindex.node.search;

import java.util.List;

import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexHit;
import ru.nsu.fit.sberlab.vectorindex.common.dto.PartitionFailure;

/**
 * Результат распределённого поиска без документов: ключи и расстояния по возрастанию
 * (все кандидаты для добора при гидрации) и полный статус выполнения.
 */
public record DistributedSearchResult(List<IndexHit> hits, int expectedPartitions, int[] searchedPartitions,
                                      List<PartitionFailure> failedPartitions, int[] knownGapPartitions,
                                      boolean timedOut, int respondedNodes, int totalNodes, int duplicateKeys) {

    /** Не все ожидаемые партиции обработаны, есть известный пробел, истёк бюджет или ответили не все. */
    public boolean partial() {
        return searchedPartitions.length < expectedPartitions || knownGapPartitions.length > 0
                || timedOut || respondedNodes < totalNodes;
    }
}
