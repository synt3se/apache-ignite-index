package ru.nsu.fit.sberlab.vectorindex.node.compute.nodework;

import java.util.List;
import java.util.function.LongPredicate;

import org.apache.ignite.compute.ComputeJobAdapter;
import ru.nsu.fit.sberlab.vectorindex.common.ScoredVector;
import ru.nsu.fit.sberlab.vectorindex.common.filter.VectorMetadataFilter;
import ru.nsu.fit.sberlab.vectorindex.node.index.NodeIndexContext;
import ru.nsu.fit.sberlab.vectorindex.node.index.PartitionIndexManager;

public class SearchLocalIndexJob extends ComputeJobAdapter {
    private final float[] queryVector;
    private final int count;
    private final String filter;

    public SearchLocalIndexJob(float[] queryVector, int count, String filter) {
        this.queryVector = queryVector;
        this.count = count;
        this.filter = filter;
    }

    @Override
    public Object execute() {
        PartitionIndexManager manager = NodeIndexContext.manager();
        if (manager == null) return List.<ScoredVector>of(); // узел ещё поднимается
        return manager.searchLocal(queryVector, count, filter);
    }
}