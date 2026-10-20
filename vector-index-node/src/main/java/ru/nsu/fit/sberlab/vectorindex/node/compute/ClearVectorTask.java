package ru.nsu.fit.sberlab.vectorindex.node.compute;

import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.compute.ComputeJobResult;
import org.apache.ignite.compute.ComputeTaskAdapter;
import ru.nsu.fit.sberlab.vectorindex.node.compute.nodework.ClearLocalIndexJob;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Совместимость bulk-загрузки: очистка индексов кэша (аргумент; {@code null} — демо-кэш {@code vectors}). */
public class ClearVectorTask extends ComputeTaskAdapter<String, Void> {
    @Override
    public Map<? extends ComputeJob, ClusterNode> map(
            List<ClusterNode> subgrid,
            String arg
    ) {
        Map<ComputeJob, ClusterNode> jobs = new HashMap<>();

        for (ClusterNode node : subgrid) {
            jobs.put(new ClearLocalIndexJob(arg == null ? "vectors" : arg), node);
        }

        return jobs;
    }

    @Override
    public Void reduce(List<ComputeJobResult> results) {
        return null;
    }
}