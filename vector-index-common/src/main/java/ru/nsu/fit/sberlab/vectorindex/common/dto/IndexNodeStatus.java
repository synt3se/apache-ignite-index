package ru.nsu.fit.sberlab.vectorindex.common.dto;

import java.io.Serializable;

/**
 * Готовность и метрики одного индекса на одном узле (§7.5, §7.8). Общие ресурсы узла (heap,
 * общая очередь применения, пулы) сюда не входят — они в {@link NodeStats}, чтобы не считать
 * их по разу на каждый индекс.
 */
public class IndexNodeStatus implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum State {
        LOADED,
        /** Индекс настроен на узле, но выгружен (до рестарта узла). */
        UNLOADED,
        /** Узел не знает такого {@code indexId}. */
        UNKNOWN_INDEX
    }

    public String nodeId;
    public String indexId;
    public State state;
    public String fingerprint;
    public String algorithm;
    public String metric;
    public int dimension;

    /** Партиции, для которых на узле есть runtime текущего эпизода владения. */
    public int[] ownedPartitions = new int[0];
    public int[] readyPartitions = new int[0];
    public int[] loadingPartitions = new int[0];
    /** Известный пробел: ожидающая сверка. */
    public int[] repairPendingPartitions = new int[0];
    public long maxRepairPendingAgeMs;

    public int optimizationNotBuilt;
    public int optimizationBuilding;
    public int optimizationBuilt;
    public int optimizationFailed;

    public long liveVectors;
    public long deltaEntries;
    public int frozenLayers;

    /** Принятые к применению изменения, ещё не завершённые: в очереди и выполняющиеся. */
    public long pendingApplies;
    /** Сканы в очереди и выполняющиеся. */
    public int scansInProgress;

    public long appliedTotal;
    public long applyLatencyNanosTotal;
    public long applyLatencyNanosMax;
    public long applierOverflowsTotal;
    public long applyErrorsTotal;
    public long unsupportedKeysTotal;
    /** Поле вектора есть, но его тип не {@code float[]}: запись исключена из проекции. */
    public long wrongTypeValuesTotal;
    /** Значение без поля вектора: не индексируется. */
    public long noVectorValuesTotal;
    public long rejectedVectorsTotal;
    public long dedupSkippedTotal;
    public long buildFailuresTotal;
    public long publishRejectedTotal;
    public long writerLockWaitNanosMax;
    public long lastBuildDurationNanosMax;
    public long lastPublishLatencyNanosMax;
    /** Оценка heap индекса — формула, не замер. */
    public long indexMemoryEstimateBytes;
    public String sourcePosition = "UNKNOWN";

    public IndexNodeStatus() {
    }
}
