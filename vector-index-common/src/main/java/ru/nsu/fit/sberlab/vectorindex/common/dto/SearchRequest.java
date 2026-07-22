package ru.nsu.fit.sberlab.vectorindex.common.dto;

import ru.nsu.fit.sberlab.vectorindex.common.validation.ValidDimension;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

public record SearchRequest(
        @NotNull(message = "vector is required")
        @ValidDimension
        float[] vector,
        
        @Positive(message = "count must be greater than zero")
        Integer count,
        String filter
){}