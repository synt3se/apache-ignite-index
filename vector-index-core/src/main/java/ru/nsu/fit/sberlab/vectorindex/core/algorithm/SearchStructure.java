package ru.nsu.fit.sberlab.vectorindex.core.algorithm;

import ru.nsu.fit.sberlab.vectorindex.core.Budget;
import ru.nsu.fit.sberlab.vectorindex.core.Completion;

/**
 * Поисковая структура базы, построенная алгоритмом. Потокобезопасна для параллельных поисков.
 * Временем жизни владеет ядро: структура закрывается, когда базу отпустит последний читатель.
 */
public interface SearchStructure extends AutoCloseable {

    /**
     * Передаёт в {@code out} кандидатов среди допустимых {@code accept} ordinals.
     *
     * @param preparedQuery подготовленный ядром запрос; только чтение
     * @param budget        алгоритм проверяет его перед дорогими этапами; один неделимый шаг
     *                      (например, обход графа) может его превысить
     * @return {@link Completion#BUDGET_EXHAUSTED}, если работа прервана по бюджету
     */
    Completion search(float[] preparedQuery, int k, AlgorithmSearchParams params,
                      OrdinalFilter accept, Budget budget, CandidateConsumer out);

    /** Оценка собственной памяти структуры без векторов, принадлежащих ядру. */
    long estimatedBytes();

    @Override
    void close();
}
