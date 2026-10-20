package ru.nsu.fit.sberlab.vectorindex.common.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Ответ распределённого поиска. {@code partial} — не все ожидаемые партиции обработаны,
 * есть известный пробел, истёк бюджет или ответили не все узлы; k результатов не доказывают
 * полноты. Подробности — в полях покрытия.
 */
public class SearchResponse implements Serializable {
    public List<SearchHit> results;
    public long tookMs;
    public int respondedNodes;
    public int totalNodes;
    /** Совместимость: число обработанных партиций (каждая — один раз). */
    public int activePartitions;
    /** Совместимость: ожидаемое число партиций кэша. */
    public int totalPartitions;
    public boolean partial;

    public int[] searchedPartitions = new int[0];
    public List<PartitionFailure> failedPartitions = new ArrayList<>();
    public int[] knownGapPartitions = new int[0];
    /** Исчерпан общий бюджет запроса (включая гидрацию). */
    public boolean timedOut;
    /** Повторы ключа между ответами — нарушение назначения партиций, а не выбор версии. */
    public int duplicateKeys;

    public SearchResponse() {}
}
