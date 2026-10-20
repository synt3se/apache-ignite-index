package ru.nsu.fit.sberlab.vectorindex.vectorserver.search;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;

/** Документы демо-кэша по ключам; ожидание ограничивает вызывающий бюджетом запроса. */
public interface DocumentStore {
    CompletableFuture<Map<Long, VectorObject>> getAll(Set<Long> ids);
}
