package ru.nsu.fit.sberlab.vectorindex.core.algorithm;

/**
 * Параметры поиска конкретного алгоритма (JVector: {@code ef}; Flat: нет; IVF: {@code nprobe}).
 * Общего фиктивного {@code ef} нет: совместимость проверяет {@link VectorAlgorithm#validate}.
 */
public interface AlgorithmSearchParams {
}
