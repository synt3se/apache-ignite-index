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

    public NodeStats() {}
}
