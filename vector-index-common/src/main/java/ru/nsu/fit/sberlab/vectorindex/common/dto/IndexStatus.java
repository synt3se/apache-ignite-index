package ru.nsu.fit.sberlab.vectorindex.common.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Готовность индекса по кластеру. Партиции классифицируются по назначенному primary-владельцу
 * на момент запроса статуса.
 *
 * <p>{@link #observedReady} — наблюдаемый признак: все ожидаемые партиции готовы, известных
 * пробелов нет, нет выполняющихся применений и сканов, все серверы ответили и согласны
 * с определением. Это <b>не доказательство</b>, что индекс получил все подтверждённые записи:
 * позиция источника неизвестна, а событие может быть ещё не доставлено. Барьер для бенчмарка —
 * отдельный протокол (этап D).
 */
public class IndexStatus implements Serializable {

    private static final long serialVersionUID = 1L;

    public String indexId;
    public String fingerprint;
    public long topologyVersion;
    public int expectedPartitions;

    /** READY у назначенного владельца. */
    public int[] readyPartitions = new int[0];
    public int[] loadingPartitions = new int[0];
    /** Без владельца, без runtime у владельца, владелец не ответил, выгрузил индекс или не согласен с определением. */
    public int[] missingPartitions = new int[0];
    public int[] repairPendingPartitions = new int[0];
    public long maxRepairPendingAgeMs;

    public int optimizationNotBuilt;
    public int optimizationBuilding;
    public int optimizationBuilt;
    public int optimizationFailed;

    public long liveVectors;
    public long deltaEntries;
    public int frozenLayers;
    public long pendingApplies;
    public int scansInProgress;

    public long rejectedVectorsTotal;
    public long wrongTypeValuesTotal;
    public long unsupportedKeysTotal;
    public long applyErrorsTotal;
    public long applierOverflowsTotal;

    /** Серверы, не ответившие на запрос статуса. */
    public List<String> unknownNodes = new ArrayList<>();
    public List<String> unloadedNodes = new ArrayList<>();
    public List<String> unknownIndexNodes = new ArrayList<>();
    /** Серверы, у которых определение с тем же {@code indexId} отличается. */
    public List<String> mismatchedNodes = new ArrayList<>();

    /** Все ожидаемые партиции READY у назначенных владельцев. */
    public boolean searchable;
    /** Есть ожидающая сверка хотя бы на одной партиции. */
    public boolean knownGap;
    public boolean observedReady;
    public String sourcePosition = "UNKNOWN";

    public List<IndexNodeStatus> nodes = new ArrayList<>();

    public IndexStatus() {
    }
}
