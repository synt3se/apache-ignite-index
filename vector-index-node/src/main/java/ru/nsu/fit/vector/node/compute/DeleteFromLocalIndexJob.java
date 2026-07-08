package ru.nsu.fit.vector.node.compute;

import org.apache.ignite.Ignite;
import org.apache.ignite.lang.IgniteRunnable;
import org.apache.ignite.resources.IgniteInstanceResource;
import ru.nsu.fit.vector.node.index.NodeLocalVectorIndex;
import ru.nsu.fit.vector.node.index.NodeLocalVectorIndexHolder;


public class DeleteFromLocalIndexJob implements IgniteRunnable {
    private final long id;

    @IgniteInstanceResource
    private transient Ignite ignite;

    public DeleteFromLocalIndexJob(long id) {
        this.id = id;
    }

    @Override
    public void run() {
        NodeLocalVectorIndex index =
                NodeLocalVectorIndexHolder.getOrCreate(ignite);

        index.deleteLocal(id);
    }
}