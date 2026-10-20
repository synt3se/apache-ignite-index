package ru.nsu.fit.sberlab.vectorindex.core;

import java.util.List;

/**
 * Результат поиска по одному виду.
 *
 * @param completion     уложился ли поиск в бюджет
 * @param hits           top-k по возрастанию расстояния; пуст при {@link Completion#BUDGET_EXHAUSTED}
 * @param annUnderfilled ANN вернул меньше кандидатов, чем мог, а бюджета на добор не хватило
 *                       (вопрос качества ANN, не покрытия)
 */
public record LocalSearchResult<K>(Completion completion, List<Hit<K>> hits, boolean annUnderfilled) {

    public LocalSearchResult {
        hits = List.copyOf(hits);
    }

    static <K> LocalSearchResult<K> exhausted() {
        return new LocalSearchResult<>(Completion.BUDGET_EXHAUSTED, List.of(), false);
    }

    public boolean complete() {
        return completion == Completion.COMPLETE;
    }
}
