package ru.nsu.fit.sberlab.vectorindex.node.compute;

import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.compute.ComputeJobAdapter;
import org.apache.ignite.compute.ComputeJobResult;
import org.apache.ignite.compute.ComputeTaskAdapter;
import ru.nsu.fit.sberlab.vectorindex.node.index.NodeIndexContext;
import ru.nsu.fit.sberlab.vectorindex.node.index.PartitionIndexManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RebuildIndexesTask extends ComputeTaskAdapter<Void, Void> {

    @Override
    public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, Void arg) {
        Map<ComputeJob, ClusterNode> jobs = new HashMap<>();
        for (ClusterNode node : subgrid) jobs.put(new RebuildIndexesJob(), node);
        return jobs;
    }

    @Override
    public Void reduce(List<ComputeJobResult> results) {
        for (ComputeJobResult result : results) {
            if (result.getException() != null) throw result.getException();
        }
        return null;
    }

    private static class RebuildIndexesJob extends ComputeJobAdapter {
        @Override
        public Object execute() {
            PartitionIndexManager manager = NodeIndexContext.manager();
            if (manager == null) throw new IllegalStateException("PartitionIndexManager is not initialized");

            manager.resumeIndexing();   // снимает bulk-паузу и делает полный rebuild
            return null;
        }
    }
}