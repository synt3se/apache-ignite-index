package ru.nsu.fit.vector.node.compute;

import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.compute.ComputeJobResult;
import org.apache.ignite.compute.ComputeTaskAdapter;
import ru.nsu.fit.vector.node.compute.nodework.ClearLocalIndexJob;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClearVectorTask extends ComputeTaskAdapter<Void, Void> {
    @Override
    public Map<? extends ComputeJob, ClusterNode> map(
            List<ClusterNode> subgrid,
            Void arg
    ) {
        Map<ComputeJob, ClusterNode> jobs = new HashMap<>();

        for (ClusterNode node : subgrid) {
            jobs.put(new ClearLocalIndexJob(), node);
        }

        return jobs;
    }

    @Override
    public Void reduce(List<ComputeJobResult> results) {
        return null;
    }
}