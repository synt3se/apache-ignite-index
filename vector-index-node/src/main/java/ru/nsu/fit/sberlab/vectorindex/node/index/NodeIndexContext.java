package ru.nsu.fit.sberlab.vectorindex.node.index;

import org.apache.ignite.Ignite;

/**
 * Адаптер индекса конкретного узла: хранится в {@code nodeLocalMap} экземпляра Ignite,
 * а не в статическом поле JVM — два узла в одной JVM видят каждый свой индекс.
 * Несколько {@code indexId} на узел — этап C.
 */
public final class NodeIndexContext {

    private static final String KEY = "ru.nsu.fit.sberlab.vectorindex.adapter";

    private NodeIndexContext() {
    }

    static void register(Ignite ignite, IgniteIndexAdapter adapter) {
        ignite.cluster().nodeLocalMap().put(KEY, adapter);
    }

    static void unregister(Ignite ignite, IgniteIndexAdapter adapter) {
        ignite.cluster().nodeLocalMap().remove(KEY, adapter);
    }

    /** Адаптер узла или {@code null}, если индекс на нём не запущен. */
    public static IgniteIndexAdapter manager(Ignite ignite) {
        return ignite == null ? null : (IgniteIndexAdapter) ignite.cluster().nodeLocalMap().get(KEY);
    }
}
