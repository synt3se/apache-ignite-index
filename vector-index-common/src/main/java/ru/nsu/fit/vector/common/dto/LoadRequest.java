package ru.nsu.fit.vector.common.dto;

import javax.validation.constraints.NotBlank;

public record LoadRequest(
        @NotBlank(message = "url is required")
        String file
){}
