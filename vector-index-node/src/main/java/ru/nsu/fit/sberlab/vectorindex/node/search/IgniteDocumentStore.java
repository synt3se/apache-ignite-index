package ru.nsu.fit.sberlab.vectorindex.node.search;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.apache.ignite.IgniteCache;

import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;

/** Документы демо-кэша асинхронным {@code getAll}: ожидание ограничивает координатор. */
public final class IgniteDocumentStore implements DocumentStore {

    private final IgniteCache<Long, VectorObject> cache;

    public IgniteDocumentStore(IgniteCache<Long, VectorObject> cache) {
        this.cache = cache;
    }

    @Override
    public CompletableFuture<Map<Long, VectorObject>> getAll(Set<Long> ids) {
        return IgniteFutures.toCompletable(cache.getAllAsync(ids));
    }
}
