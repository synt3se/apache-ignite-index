package ru.nsu.fit.vector.common.dto;

import java.io.Serializable;
import java.util.List;
import ru.nsu.fit.vector.common.ScoredVector;

public class NodeSearchResult implements Serializable {
    public List<ScoredVector> results;
    public int activePartitions;

    public NodeSearchResult() {}

    public NodeSearchResult(List<ScoredVector> results, int activePartitions) {
        this.results = results;
        this.activePartitions = activePartitions;
    }
}
