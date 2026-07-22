package ru.nsu.fit.sberlab.vectorindex.common.dto;

public record VectorResponse(Long id, float[] vector, String url, String metadata) {}
