package ru.nsu.fit.sberlab.vectorindex.common.dto;

import java.io.Serializable;
import java.util.List;

public class ClusterStats implements Serializable {
    public int serverNodes;
    public long totalLiveVectors;
    public long totalIndexMemoryEstimateBytes;
    public List<NodeStats> nodes;

    public ClusterStats() {}
}