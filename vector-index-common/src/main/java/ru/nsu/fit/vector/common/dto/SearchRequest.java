package ru.nsu.fit.vector.common.dto;

import ru.nsu.fit.vector.common.validation.ValidDimension;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

public record SearchRequest(
        @NotNull(message = "vector is required")
        @ValidDimension
        float[] vector,
        
        @Positive(message = "count must be greater than zero")
        Integer count
){}