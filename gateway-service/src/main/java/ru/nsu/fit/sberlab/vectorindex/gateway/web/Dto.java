package ru.nsu.fit.sberlab.vectorindex.gateway.web;

public final class Dto {
    private Dto() {}

    public record Neighbor(long id, double score, String url, String metadata) {}

    // GET /image/{id}
    public record VectorResponse(long id, float[] vector, String url, String metadata) {}

    public record TextSearchRequest(String text, Integer count, String filter) {}

    public record EmbeddingResponse(float[] vector) {}

    public record ClipTextRequest(String text) {}

    public record IndexAddRequest(float[] vector, String url, String metadata) {}

    public record IndexSearchRequest(float[] vector, Integer count, String filter) {}

    public record AddImageUrlRequest(String url, String metadata) {}

    public record SearchImageUrlRequest(String url, Integer count, String filter) {}
    public record SearchVectorRequest(float[] vector, Integer count, String filter) {}
}
