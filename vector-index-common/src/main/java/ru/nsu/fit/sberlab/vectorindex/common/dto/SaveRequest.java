package ru.nsu.fit.sberlab.vectorindex.common.dto;

import javax.validation.constraints.NotBlank;

public record SaveRequest(
        @NotBlank(message = "url is required")
        String file
){}
