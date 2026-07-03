package ru.nsu.fit.vectorserver;

import java.io.Serializable;

public class VectorObject implements Serializable {
    private float[] vector;
    private String url;
    private String metadata;

    public VectorObject() {
    }

    public VectorObject(float[] vector, String url, String metadata) {
        this.vector = vector;
        this.url = url;
        this.metadata = metadata;
    }

    public float[] getVector() {
        return vector;
    }

    public void setVector(float[] vector) {
        this.vector = vector;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }
}