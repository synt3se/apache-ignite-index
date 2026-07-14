package ru.nsu.fit.vector.common.dto;

import java.io.Serializable;
import java.util.List;

public class SearchResponse implements Serializable {
    public List<SearchHit> results;
    public long tookMs;
    public int respondedNodes;
    public int totalNodes;
    public int activePartitions;
    public int totalPartitions;
    public boolean partial;

    public SearchResponse() {}
}
