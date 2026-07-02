package com.github.synt3se.gateway.web;

public final class Dto {
    private Dto() {}

    public record Neighbor(long id, double score, String url, String metadata) {}

    // GET /image/{id}
    public record VectorResponse(long id, float[] vector, String url, String metadata) {}

    public record TextSearchRequest(String text, Integer k) {}

    public record EmbeddingResponse(float[] vector) {}

    public record IndexAddRequest(long id, float[] vector, String url, String metadata) {}

    public record IndexSearchRequest(float[] vector, Integer k) {}

    public record AddImageUrlRequest(String url, String metadata) {}

    public record SearchImageUrlRequest(String url, Integer k, String metadata) {}
    public record SearchVectorRequest(float[] vector, Integer k) {}
}
