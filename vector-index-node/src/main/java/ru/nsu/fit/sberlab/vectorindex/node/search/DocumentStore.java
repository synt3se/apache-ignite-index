package ru.nsu.fit.sberlab.vectorindex.node.search;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;

/** Документы демо-приложения по ключам; ожидание ограничивается бюджетом запроса. */
public interface DocumentStore {
    CompletableFuture<Map<Long, VectorObject>> getAll(Set<Long> ids);
}
