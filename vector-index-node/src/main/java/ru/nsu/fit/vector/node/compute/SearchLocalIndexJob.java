package ru.nsu.fit.vector.node.compute;

import org.apache.ignite.Ignite;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.resources.IgniteInstanceResource;
import ru.nsu.fit.vector.common.ScoredVector;
import ru.nsu.fit.vector.node.index.NodeLocalVectorIndex;
import ru.nsu.fit.vector.node.index.NodeLocalVectorIndexHolder;


import java.util.List;

public class SearchLocalIndexJob implements IgniteCallable<List<ScoredVector>> {
    private final float[] queryVector;
    private final int count;

    @IgniteInstanceResource
    private transient Ignite ignite;

    public SearchLocalIndexJob(float[] queryVector, int count) {
        this.queryVector = queryVector;
        this.count = count;
    }

    @Override
    public List<ScoredVector> call() {
        NodeLocalVectorIndex index =
                NodeLocalVectorIndexHolder.getOrCreate(ignite);

        return index.searchLocal(queryVector, count);
    }
}