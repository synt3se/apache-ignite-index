package ru.nsu.fit.vector.node.compute.nodework;

import org.apache.ignite.compute.ComputeJobAdapter;
import ru.nsu.fit.vector.node.index.NodeIndexContext;
import ru.nsu.fit.vector.node.index.PartitionIndexManager;

public class ClearLocalIndexJob extends ComputeJobAdapter {
    @Override
    public Object execute() {
        PartitionIndexManager manager = NodeIndexContext.manager();
        if (manager != null) manager.clearAll();
        return null;
    }
}