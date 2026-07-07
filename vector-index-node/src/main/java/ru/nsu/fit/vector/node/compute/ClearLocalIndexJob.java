package ru.nsu.fit.vector.node.compute;

import org.apache.ignite.lang.IgniteRunnable;
import ru.nsu.fit.vector.node.index.NodeLocalVectorIndexRegistry;

public class ClearLocalIndexJob implements IgniteRunnable {
    private final String cacheName;

    public ClearLocalIndexJob(String cacheName) {
        this.cacheName = cacheName;
    }

    @Override
    public void run() {
        NodeLocalVectorIndexRegistry.clear(cacheName);
    }
}