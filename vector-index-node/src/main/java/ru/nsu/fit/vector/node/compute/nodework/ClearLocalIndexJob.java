package ru.nsu.fit.vector.node.compute.nodework;

import org.apache.ignite.compute.ComputeJobAdapter;
import ru.nsu.fit.vector.node.index.NodeLocalVectorIndexHolder;

public class ClearLocalIndexJob extends ComputeJobAdapter {
    @Override
    public Object execute() {
        NodeLocalVectorIndexHolder.clear();

        return null;
    }
}