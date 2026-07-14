package ru.nsu.fit.vector.common.dto;

import java.io.Serializable;

public class SearchHit implements Serializable {
    public long id;
    public double distance;
    public String url;
    public String metadata;

    public SearchHit() {}

    public SearchHit(long id, double distance, String url, String metadata) {
        this.id = id;
        this.distance = distance;
        this.url = url;
        this.metadata = metadata;
    }
}
