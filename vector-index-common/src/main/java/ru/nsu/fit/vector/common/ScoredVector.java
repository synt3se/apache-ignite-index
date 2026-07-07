package ru.nsu.fit.vector.common;

import java.io.Serializable;

public record ScoredVector(
        long id,
        double distance
) implements Serializable {
}