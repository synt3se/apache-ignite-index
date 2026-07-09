package ru.nsu.fit.vector.common.dto;

import ru.nsu.fit.vector.common.validation.ValidDimension;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

public record AddRequest(
        @NotNull(message = "vector is required")
        @ValidDimension
        float[] vector,

        @NotBlank(message = "url is required")
        String url,

        String metadata
){}
