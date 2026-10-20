package ru.nsu.fit.sberlab.vectorindex.core;

import java.util.Objects;

import ru.nsu.fit.sberlab.vectorindex.core.algorithm.AlgorithmSearchParams;

/**
 * Параметры локального поиска.
 *
 * @param k         сколько ближайших вернуть; {@code k ≤ 0} — пустой ответ
 * @param exact     точный перебор вместо ANN-структуры (эталон, проверки)
 * @param algorithm параметры конкретного алгоритма; {@code null} — значения алгоритма по умолчанию.
 *                  Несовместимые с алгоритмом параметры — {@link IllegalArgumentException}
 * @param budget    бюджет выполнения
 */
public record SearchParams(int k, boolean exact, AlgorithmSearchParams algorithm, Budget budget) {

    public SearchParams {
        Objects.requireNonNull(budget, "budget");
    }

    public static SearchParams topK(int k) {
        return new SearchParams(k, false, null, Budget.unlimited());
    }

    public static SearchParams exactTopK(int k) {
        return new SearchParams(k, true, null, Budget.unlimited());
    }

    public SearchParams withAlgorithm(AlgorithmSearchParams params) {
        return new SearchParams(k, exact, params, budget);
    }

    public SearchParams withBudget(Budget value) {
        return new SearchParams(k, exact, algorithm, value);
    }
}
