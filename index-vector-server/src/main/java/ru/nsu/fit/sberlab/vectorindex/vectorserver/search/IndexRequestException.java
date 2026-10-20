package ru.nsu.fit.sberlab.vectorindex.vectorserver.search;

import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchError;

/** Ошибка запроса к индексу: неизвестный индекс (404) или неверный запрос (400). */
public final class IndexRequestException extends RuntimeException {

    private final SearchError error;

    public IndexRequestException(SearchError error, String message) {
        super(message);
        this.error = error;
    }

    public SearchError error() {
        return error;
    }
}
