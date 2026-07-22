package ru.nsu.fit.sberlab.vectorindex.node.compute.nodework;

import java.util.List;

import org.apache.ignite.lang.IgniteCallable;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeSearchResult;
import ru.nsu.fit.sberlab.vectorindex.node.index.NodeIndexContext;
import ru.nsu.fit.sberlab.vectorindex.node.index.PartitionIndexManager;

public class NodeSearchJob implements IgniteCallable<NodeSearchResult> {
    private static final long serialVersionUID = 1L;

    private final float[] queryVector;
    private final int count;

    public NodeSearchJob(float[] queryVector, int count) {
        this.queryVector = queryVector;
        this.count = count;
    }

    @Override
    public NodeSearchResult call() {
        PartitionIndexManager manager = NodeIndexContext.manager();
        if (manager == null) {
            return new NodeSearchResult(List.of(), 0);
        }
        return new NodeSearchResult(
                manager.searchLocal(queryVector, count),
                manager.activePartitionsCount());
    }
}
