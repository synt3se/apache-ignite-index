package ru.nsu.fit.sberlab.vectorindex.node.compute.nodework;

import org.apache.ignite.compute.ComputeJobAdapter;
import ru.nsu.fit.sberlab.vectorindex.node.index.NodeIndexContext;
import ru.nsu.fit.sberlab.vectorindex.node.index.PartitionIndexManager;

import java.util.concurrent.TimeUnit;

public class ClearLocalIndexJob extends ComputeJobAdapter {
    @Override
    public Object execute() {
        PartitionIndexManager manager = NodeIndexContext.manager();
        if (manager != null) {
            manager.pauseIndexing(TimeUnit.MINUTES.toMillis(10));   // bulk-режим до RebuildIndexesTask
            manager.clearAll();
        }
        return null;
    }
}