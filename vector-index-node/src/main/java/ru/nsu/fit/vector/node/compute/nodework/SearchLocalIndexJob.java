package ru.nsu.fit.vector.node.compute.nodework;

import org.apache.ignite.Ignite;
import org.apache.ignite.compute.ComputeJobAdapter;
import org.apache.ignite.resources.IgniteInstanceResource;
import ru.nsu.fit.vector.node.index.NodeLocalVectorIndex;
import ru.nsu.fit.vector.node.index.NodeLocalVectorIndexHolder;

public class SearchLocalIndexJob extends ComputeJobAdapter {
    private final float[] queryVector;
    private final int count;

    @IgniteInstanceResource
    private transient Ignite ignite;

    public SearchLocalIndexJob(float[] queryVector, int count) {
        this.queryVector = queryVector;
        this.count = count;
    }

    @Override
    public Object execute() {
        NodeLocalVectorIndex index =
                NodeLocalVectorIndexHolder.getOrCreate(ignite);

        return index.searchLocal(queryVector, count);
    }
}