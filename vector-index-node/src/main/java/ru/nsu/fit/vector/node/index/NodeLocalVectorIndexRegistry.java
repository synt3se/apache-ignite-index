package ru.nsu.fit.vector.node.index;

import org.apache.ignite.Ignite;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NodeLocalVectorIndexRegistry { //TODO сопоставление имени кэша и индекса. Вопрос надо ли
    private static final Map<String, NodeLocalVectorIndex> INDEXES_BY_CACHE =
            new ConcurrentHashMap<>();

    private NodeLocalVectorIndexRegistry() {
    }

    public static NodeLocalVectorIndex getOrCreate(Ignite ignite, String cacheName) {
        return INDEXES_BY_CACHE.computeIfAbsent(
                cacheName,
                name -> new NodeLocalVectorIndex(ignite, name)
        );
    }

    public static void clear(String cacheName) {
        NodeLocalVectorIndex index = INDEXES_BY_CACHE.remove(cacheName);

        if (index != null) {
            index.clearLocal();
        }
    }
}