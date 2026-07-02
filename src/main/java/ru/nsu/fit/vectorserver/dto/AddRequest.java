package ru.nsu.fit.vectorserver.dto;

import java.util.List;

public record AddRequest(Long id, float[] vector, String url){}