package ru.nsu.fit.vector.node.compute.nodework;

import java.util.List;

import org.apache.ignite.compute.ComputeJobAdapter;
import ru.nsu.fit.vector.common.ScoredVector;
import ru.nsu.fit.vector.node.index.NodeIndexContext;
import ru.nsu.fit.vector.node.index.PartitionIndexManager;

public class SearchLocalIndexJob extends ComputeJobAdapter {
    private final float[] queryVector;
    private final int count;

    public SearchLocalIndexJob(float[] queryVector, int count) {
        this.queryVector = queryVector;
        this.count = count;
    }

    @Override
    public Object execute() {
        PartitionIndexManager manager = NodeIndexContext.manager();
        if (manager == null) return List.<ScoredVector>of(); // узел ещё поднимается
        return manager.searchLocal(queryVector, count);
    }
}