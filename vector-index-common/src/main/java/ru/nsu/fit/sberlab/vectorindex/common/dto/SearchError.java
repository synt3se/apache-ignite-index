package ru.nsu.fit.sberlab.vectorindex.common.dto;

/** Ошибка запроса поиска; ответ с ошибкой — {@link SearchStatus#FAILED} без попаданий. */
public enum SearchError {
    /** Координатор не знает индекса с таким {@code indexId} (REST: 404). */
    UNKNOWN_INDEX,
    /** Неверный вектор, {@code k} или параметры алгоритма (REST: 400). */
    INVALID_REQUEST
}
