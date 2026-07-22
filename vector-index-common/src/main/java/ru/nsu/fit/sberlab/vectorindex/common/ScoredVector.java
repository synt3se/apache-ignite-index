package ru.nsu.fit.sberlab.vectorindex.common;

import java.io.Serializable;

public class ScoredVector implements Serializable {
    private long id;
    private double distance;

    public ScoredVector() {
    }

    public ScoredVector(long id, double distance) {
        this.id = id;
        this.distance = distance;
    }

    public long id() {
        return id;
    }

    public double distance() {
        return distance;
    }

    public long getId() {
        return id;
    }

    public double getDistance() {
        return distance;
    }

    public void setId(long id) {
        this.id = id;
    }

    public void setDistance(double distance) {
        this.distance = distance;
    }
}