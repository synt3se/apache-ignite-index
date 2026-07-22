package ru.nsu.fit.sberlab.vectorindex.telegrambot;

public class Dto {
    public record Neighbor(long id, double score, String url, String metadata) {}
    public record VectorResponse(long id, float[] vector, String url, String metadata) {}
}
