package ru.nsu.fit.vector.common.dto;

import java.io.Serializable;

public class NodeStats implements Serializable {
    public String nodeId;
    public int ownedPartitions;
    public int activePartitions;
    public long liveVectors;
    public long applierBacklog; // отставание индекса от кэша
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

    public NodeStats() {}
}
