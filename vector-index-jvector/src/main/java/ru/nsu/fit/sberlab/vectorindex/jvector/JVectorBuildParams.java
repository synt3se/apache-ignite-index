package ru.nsu.fit.sberlab.vectorindex.jvector;

/**
 * Параметры построения графа JVector (Vamana-подобный граф с необязательной иерархией).
 * Изменение этих параметров — новая структура; параметры поиска — {@link JVectorSearchParams}.
 */
public record JVectorBuildParams(int m, int efConstruction, float neighborOverflow, float alpha,
                                 boolean addHierarchy, boolean refineFinalGraph) {

    public JVectorBuildParams {
        if (m <= 0) {
            throw new IllegalArgumentException("m must be positive: " + m);
        }
        if (efConstruction <= 0) {
            throw new IllegalArgumentException("efConstruction must be positive: " + efConstruction);
        }
        if (neighborOverflow < 1.0f) {
            throw new IllegalArgumentException("neighborOverflow must be >= 1: " + neighborOverflow);
        }
        if (alpha <= 0) {
            throw new IllegalArgumentException("alpha must be positive: " + alpha);
        }
    }
}
