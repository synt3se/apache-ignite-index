package ru.nsu.fit.vector.node.compute;

import org.apache.ignite.Ignite;
import org.apache.ignite.lang.IgniteRunnable;
import org.apache.ignite.resources.IgniteInstanceResource;
import ru.nsu.fit.vector.node.index.NodeLocalVectorIndex;
import ru.nsu.fit.vector.node.index.NodeLocalVectorIndexRegistry;

public class DeleteFromLocalIndexJob implements IgniteRunnable {
    private final String cacheName;
    private final long id;

    @IgniteInstanceResource
    private transient Ignite ignite;

    public DeleteFromLocalIndexJob(String cacheName, long id) {
        this.cacheName = cacheName;
        this.id = id;
    }

    @Override
    public void run() {
        NodeLocalVectorIndex index =
                NodeLocalVectorIndexRegistry.getOrCreate(ignite, cacheName);

        index.deleteLocal(id);
    }
}