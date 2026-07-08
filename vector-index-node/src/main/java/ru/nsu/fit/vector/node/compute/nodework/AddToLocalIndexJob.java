package ru.nsu.fit.vector.node.compute.nodework;

import org.apache.ignite.Ignite;
import org.apache.ignite.compute.ComputeJobAdapter;
import org.apache.ignite.resources.IgniteInstanceResource;
import ru.nsu.fit.vector.node.index.NodeLocalVectorIndex;
import ru.nsu.fit.vector.node.index.NodeLocalVectorIndexHolder;

public class AddToLocalIndexJob extends ComputeJobAdapter {
    private final long id;
    private final float[] vector;

    @IgniteInstanceResource
    private transient Ignite ignite;

    public AddToLocalIndexJob(long id, float[] vector) {
        this.id = id;
        this.vector = vector;
    }

    @Override
    public Object execute() {
        NodeLocalVectorIndex index =
                NodeLocalVectorIndexHolder.getOrCreate(ignite);

        index.addLocal(id, vector);

        return null; // TODO
    }
}