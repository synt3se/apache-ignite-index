package ru.nsu.fit.sberlab.vectorindex.node.compute.nodework;

import org.apache.ignite.Ignite;
import org.apache.ignite.compute.ComputeJobAdapter;
import org.apache.ignite.resources.IgniteInstanceResource;
import ru.nsu.fit.sberlab.vectorindex.node.index.IgniteIndexAdapter;
import ru.nsu.fit.sberlab.vectorindex.node.index.NodeIndexRegistry;

import java.util.concurrent.TimeUnit;

/**
 * Bulk-загрузка (К5, совместимость): после {@code cache.clear()} очищает индексы этого кэша на
 * узле и ставит их на паузу до {@code RebuildIndexesTask}. Индексы других кэшей не затрагиваются.
 */
public class ClearLocalIndexJob extends ComputeJobAdapter {
    @IgniteInstanceResource
    private transient Ignite ignite;

    private final String cacheName;

    public ClearLocalIndexJob(String cacheName) {
        this.cacheName = cacheName;
    }

    @Override
    public Object execute() {
        NodeIndexRegistry registry = NodeIndexRegistry.of(ignite);
        if (registry != null) {
            for (IgniteIndexAdapter adapter : registry.loadedAdapters()) {
                if (adapter.definition().cacheName().equals(cacheName)) {
                    adapter.pauseIndexing(TimeUnit.MINUTES.toMillis(10));   // bulk-режим до RebuildIndexesTask
                    adapter.clearAll();
                }
            }
        }
        return null;
    }
}
