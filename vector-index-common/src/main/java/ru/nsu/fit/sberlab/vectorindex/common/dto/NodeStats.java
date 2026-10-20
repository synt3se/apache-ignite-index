package ru.nsu.fit.sberlab.vectorindex.common.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Статистика узла. Общие ресурсы узла (heap, общая очередь применения, uptime) — по одному
 * значению на узел. Поля индекса ниже — индекс по умолчанию (совместимость {@code /vectors/stats}
 * и дашбордов); все индексы узла — в {@link #indexes}.
 */
public class NodeStats implements Serializable {
    public String nodeId;
    /** Индекс, которому соответствуют поля индекса ниже. */
    public String indexId;
    public List<IndexNodeStatus> indexes = new ArrayList<>();
    public int ownedPartitions;
    public int activePartitions;
    public long liveVectors;
    public long applierBacklog; // общая очередь применения узла (все индексы)
    public int dirtyPartitions;
    public long appliedTotal; // сколько изменений применено с запуска
    public long enginePendingVectors;
    public long dedupSkippedTotal;   // применения, свёрнутые дедупом движка (значение уже в графе)

    public String engine;                  // что работает на узле: JVECTOR_INDEX | BRUTE_FORCE_INDEX
    public int dimension;
    public long indexMemoryEstimateBytes;  // ОЦЕНКА heap-памяти индексов узла (формула, не замер)
    public long heapUsedBytes;             // фактический heap JVM узла
    public long heapMaxBytes;
    public long uptimeMs;                  // возраст JVM узла (наглядно после рестарта в демо)

    // Готовность и метрики ядра (§7.5, §7.8): суммы по runtime узла.
    public int loadingPartitions;          // runtime в начальной загрузке
    public int repairPendingPartitions;    // известный пробел: ожидающая сверка (= dirtyPartitions)
    public long maxRepairPendingAgeMs;
    public long deltaEntries;              // vindex_delta_entries
    public int frozenLayers;               // vindex_frozen_layers
    public int optimizationFailedPartitions;
    public long buildFailuresTotal;        // vindex_build_failures_total
    public long publishRejectedTotal;      // vindex_publish_rejected_total
    public long rejectedVectorsTotal;      // vindex_rejected_vectors_total
    public long unsupportedKeysTotal;      // события с ключом неподдерживаемого типа (в v1 — не Long)
    public long applierOverflowsTotal;     // уведомления, не принятые очередью применения
    public long applyErrorsTotal;          // ошибки чтения источника при применении
    public long applyLatencyNanosMax;      // vindex_apply_latency: событие → применение (максимум)
    public long applyLatencyNanosTotal;    // и сумма, для среднего по appliedTotal
    public long writerLockWaitNanosMax;    // vindex_writer_lock_wait
    public long lastBuildDurationNanosMax; // vindex_build_duration (максимум последних сборок)
    public long lastPublishLatencyNanosMax;// vindex_publish_latency
    public String sourcePosition = "UNKNOWN";

    public NodeStats() {}
}
