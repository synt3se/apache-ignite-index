package ru.nsu.fit.vector.node.compute.nodework;

import org.apache.ignite.compute.ComputeJobAdapter;
import ru.nsu.fit.vector.common.dto.NodeStats;
import ru.nsu.fit.vector.node.index.NodeIndexContext;
import ru.nsu.fit.vector.node.index.PartitionIndexManager;

public class NodeStatsJob extends ComputeJobAdapter {
    @Override
    public Object execute() {
        PartitionIndexManager m = NodeIndexContext.manager();
        if (m == null) {
            NodeStats s = new NodeStats();
            s.nodeId = "(starting)";
            return s;
        }
        return m.localStats();
    }
}