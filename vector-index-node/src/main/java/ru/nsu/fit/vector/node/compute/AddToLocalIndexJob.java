package ru.nsu.fit.vector.node.compute;

import org.apache.ignite.Ignite;
import org.apache.ignite.lang.IgniteRunnable;
import org.apache.ignite.resources.IgniteInstanceResource;
import ru.nsu.fit.vector.node.index.NodeLocalVectorIndex;
import ru.nsu.fit.vector.node.index.NodeLocalVectorIndexRegistry;

public class AddToLocalIndexJob implements IgniteRunnable {
    private final String cacheName;
    private final long id;
    private final float[] vector;

    @IgniteInstanceResource
    private transient Ignite ignite;

    public AddToLocalIndexJob(String cacheName, long id, float[] vector) {
        this.cacheName = cacheName;
        this.id = id;
        this.vector = vector;
    }

    @Override
    public void run() {
        NodeLocalVectorIndex index =
                NodeLocalVectorIndexRegistry.getOrCreate(ignite, cacheName);

        index.addLocal(id, vector);
    }
}