package ru.nsu.fit.sberlab.vectorindex.jvector;

import ru.nsu.fit.sberlab.vectorindex.core.algorithm.AlgorithmSearchParams;

/**
 * Ширина поиска по графу — абсолютная, не множитель {@code k}: {@code ef ≥ k}.
 * Свип качества делается сменой {@code ef} в запросе без перестроения графа.
 */
public record JVectorSearchParams(int ef) implements AlgorithmSearchParams {

    public JVectorSearchParams {
        if (ef <= 0) {
            throw new IllegalArgumentException("ef must be positive: " + ef);
        }
    }
}
