package ru.nsu.fit.vector.common.dto;

public record VectorResponse(Long id, float[] vector, String url, String metadata) {}
