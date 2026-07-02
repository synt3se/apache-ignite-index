package ru.nsu.fit.vectorserver.dto;

public record AddRequest(Long id, float[] vector, String url){}