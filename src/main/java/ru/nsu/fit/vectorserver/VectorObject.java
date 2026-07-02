package ru.nsu.fit.vectorserver;

import java.io.Serializable;

public class VectorObject implements Serializable {
    private float[] vector;
    private String url;

    public VectorObject() {
    }

    public VectorObject(float[] vector, String url) {
        this.vector = vector;
        this.url = url;
    }

    public float[] getVector() {
        return vector;
    }

    public String getUrl() {
        return url;
    }

    public void setVector(float[] vector) {
        this.vector = vector;
    }

    public void setUrl(String url) {
        this.url = url;
    }

}