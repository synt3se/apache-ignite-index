package ru.nsu.fit.sberlab.vectorindex.node.compute;

import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.compute.ComputeJobResult;
import org.apache.ignite.compute.ComputeTaskAdapter;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeStats;
import ru.nsu.fit.sberlab.vectorindex.node.compute.nodework.NodeStatsJob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StatsTask extends ComputeTaskAdapter<Void, List<NodeStats>> {

    @Override
    public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, Void arg) {
        Map<ComputeJob, ClusterNode> jobs = new HashMap<>();
        for (ClusterNode node : subgrid) jobs.put(new NodeStatsJob(), node);
        return jobs;
    }

    @Override
    public List<NodeStats> reduce(List<ComputeJobResult> results) {
        List<NodeStats> out = new ArrayList<>();
        for (ComputeJobResult r : results) {
            if (r.getException() != null) continue;
            NodeStats s = (NodeStats) r.getData();
            if (s != null) out.add(s);
        }
        return out;
    }
}