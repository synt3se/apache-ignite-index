package ru.nsu.fit.vectorserver.dto;

public record VectorResponse(Long id, float[] vector, String url) {}
