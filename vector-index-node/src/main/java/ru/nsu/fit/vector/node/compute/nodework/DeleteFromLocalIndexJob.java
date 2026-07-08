package ru.nsu.fit.vector.node.compute.nodework;

import org.apache.ignite.Ignite;
import org.apache.ignite.compute.ComputeJobAdapter;
import org.apache.ignite.resources.IgniteInstanceResource;
import ru.nsu.fit.vector.node.index.NodeLocalVectorIndex;
import ru.nsu.fit.vector.node.index.NodeLocalVectorIndexHolder;

public class DeleteFromLocalIndexJob extends ComputeJobAdapter {
    private final long id;

    @IgniteInstanceResource
    private transient Ignite ignite;

    public DeleteFromLocalIndexJob(long id) {
        this.id = id;
    }

    @Override
    public Object execute() {
        NodeLocalVectorIndex index =
                NodeLocalVectorIndexHolder.getOrCreate(ignite);

        index.deleteLocal(id);

        return null;
    }
}