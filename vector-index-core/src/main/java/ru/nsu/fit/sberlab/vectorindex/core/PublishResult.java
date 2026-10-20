package ru.nsu.fit.sberlab.vectorindex.core;

/** Итог публикации результата сборки или скана. */
public enum PublishResult {
    PUBLISHED,
    /**
     * Runtime закрыт, токен или сессия устарели (состояние сменилось после захвата).
     * Результат закрыт ядром; поздняя работа ничего не оживляет.
     */
    REJECTED_STALE
}
