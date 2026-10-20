package ru.nsu.fit.sberlab.vectorindex.core;

/** Завершился ли локальный поиск в пределах бюджета. */
public enum Completion {
    COMPLETE,
    /** Бюджет исчерпан или запрос отменён: результатов нет, партиция не считается обработанной. */
    BUDGET_EXHAUSTED
}
