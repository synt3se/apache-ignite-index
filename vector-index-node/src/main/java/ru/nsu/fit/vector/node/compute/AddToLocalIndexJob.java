package ru.nsu.fit.vector.node.compute;

import org.apache.ignite.Ignite;
import org.apache.ignite.lang.IgniteRunnable;
import org.apache.ignite.resources.IgniteInstanceResource;
import ru.nsu.fit.vector.node.index.NodeLocalVectorIndex;
import ru.nsu.fit.vector.node.index.NodeLocalVectorIndexHolder;

public class AddToLocalIndexJob implements IgniteRunnable {
    private final long id;
    private final float[] vector;

    @IgniteInstanceResource
    private transient Ignite ignite;

    public AddToLocalIndexJob(long id, float[] vector) {
        this.id = id;
        this.vector = vector;
    }

    @Override
    public void run() {
        NodeLocalVectorIndex index =
                NodeLocalVectorIndexHolder.getOrCreate(ignite);

        index.addLocal(id, vector);
    }
}