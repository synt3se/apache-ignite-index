package ru.nsu.fit.vectorserver.dto;

public record SearchRequest(float[] vector, Integer count){}