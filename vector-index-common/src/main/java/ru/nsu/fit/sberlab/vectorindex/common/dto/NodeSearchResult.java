package ru.nsu.fit.sberlab.vectorindex.common.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import ru.nsu.fit.sberlab.vectorindex.common.ScoredVector;

/**
 * Ответ узла: top-k по партициям, реально обработанным в этом проходе, и их готовность.
 * Партиция без результата перечисляется в {@link #failedPartitions} с причиной.
 */
public class NodeSearchResult implements Serializable {
    public List<ScoredVector> results;
    /** Совместимость: число обработанных партиций ({@code searchedPartitions.length}). */
    public int activePartitions;
    /** Партиции, вид которых был открыт в фазе READY и поиск по которым завершился. */
    public int[] searchedPartitions = new int[0];
    /** Обработанные партиции с известным пробелом относительно источника (ожидающая сверка). */
    public int[] knownGapPartitions = new int[0];
    public List<PartitionFailure> failedPartitions = new ArrayList<>();
    /** Версия топологии на узле в момент поиска (диагностика). */
    public long topologyVersion;
    public String nodeId;

    public NodeSearchResult() {}

    public NodeSearchResult(List<ScoredVector> results, int activePartitions) {
        this.results = results;
        this.activePartitions = activePartitions;
    }
}
