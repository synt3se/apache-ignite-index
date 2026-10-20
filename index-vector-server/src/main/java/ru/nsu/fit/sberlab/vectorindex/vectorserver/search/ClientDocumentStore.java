package ru.nsu.fit.sberlab.vectorindex.vectorserver.search;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.apache.ignite.client.ClientCache;

import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;

/** Документы демо-кэша через тонкий клиент: асинхронный {@code getAll}, ожидание ограничивает гидратор. */
public final class ClientDocumentStore implements DocumentStore {

    private final ClientCache<Long, VectorObject> cache;

    public ClientDocumentStore(ClientCache<Long, VectorObject> cache) {
        this.cache = cache;
    }

    @Override
    public CompletableFuture<Map<Long, VectorObject>> getAll(Set<Long> ids) {
        return cache.getAllAsync(ids).toCompletableFuture();
    }
}
