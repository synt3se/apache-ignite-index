package ru.nsu.fit.sberlab.vectorindex.node.compute.nodework;

import org.apache.ignite.Ignite;
import org.apache.ignite.compute.ComputeJobAdapter;
import org.apache.ignite.resources.IgniteInstanceResource;
import ru.nsu.fit.sberlab.vectorindex.node.index.IgniteIndexAdapter;
import ru.nsu.fit.sberlab.vectorindex.node.index.NodeIndexContext;

import java.util.concurrent.TimeUnit;

public class ClearLocalIndexJob extends ComputeJobAdapter {
    @IgniteInstanceResource
    private transient Ignite ignite;

    @Override
    public Object execute() {
        IgniteIndexAdapter adapter = NodeIndexContext.manager(ignite);
        if (adapter != null) {
            adapter.pauseIndexing(TimeUnit.MINUTES.toMillis(10));   // bulk-режим до RebuildIndexesTask
            adapter.clearAll();
        }
        return null;
    }
}
