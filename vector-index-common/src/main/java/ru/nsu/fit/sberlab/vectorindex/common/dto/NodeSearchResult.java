package ru.nsu.fit.sberlab.vectorindex.common.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Ответ узла: top-k по партициям, реально обработанным в этом проходе, и их готовность.
 * Партиция без результата перечисляется в {@link #failedPartitions} с причиной.
 */
public class NodeSearchResult implements Serializable {
    /** Ключи источника ({@link KeyType}) и расстояния по возрастанию. */
    public List<IndexHit> hits = new ArrayList<>();
    /** Партиции, вид которых был открыт в фазе READY и поиск по которым завершился. */
    public int[] searchedPartitions = new int[0];
    /** Обработанные партиции с известным пробелом относительно источника (ожидающая сверка). */
    public int[] knownGapPartitions = new int[0];
    public List<PartitionFailure> failedPartitions = new ArrayList<>();
    /** Версия топологии на узле в момент поиска (диагностика). */
    public long topologyVersion;
    public String nodeId;

    public NodeSearchResult() {}

    /** Ответ без поиска: все назначенные партиции отклонены по одной причине. */
    public static NodeSearchResult rejected(int[] partitions, PartitionFailure.Reason reason, String nodeId) {
        NodeSearchResult result = new NodeSearchResult();
        result.nodeId = nodeId;
        if (partitions != null) {
            for (int partition : partitions) {
                result.failedPartitions.add(new PartitionFailure(partition, reason, nodeId));
            }
        }
        return result;
    }
}
