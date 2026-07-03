package ru.nsu.fit.vectorserver.dto;

public record AddRequest(float[] vector, String url, String metadata){}
