package ru.nsu.fit.sberlab.vectorindex.node.compute.nodework;

import org.apache.ignite.Ignite;
import org.apache.ignite.compute.ComputeJobAdapter;
import org.apache.ignite.resources.IgniteInstanceResource;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeStats;
import ru.nsu.fit.sberlab.vectorindex.node.index.IgniteIndexAdapter;
import ru.nsu.fit.sberlab.vectorindex.node.index.NodeIndexContext;

public class NodeStatsJob extends ComputeJobAdapter {
    @IgniteInstanceResource
    private transient Ignite ignite;

    @Override
    public Object execute() {
        IgniteIndexAdapter adapter = NodeIndexContext.manager(ignite);
        if (adapter == null) {
            NodeStats s = new NodeStats();
            s.nodeId = "(starting)";
            return s;
        }
        return adapter.localStats();
    }
}
