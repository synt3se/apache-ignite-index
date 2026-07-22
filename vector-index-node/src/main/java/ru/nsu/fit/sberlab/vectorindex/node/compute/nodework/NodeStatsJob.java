package ru.nsu.fit.sberlab.vectorindex.node.compute.nodework;

import org.apache.ignite.compute.ComputeJobAdapter;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeStats;
import ru.nsu.fit.sberlab.vectorindex.node.index.NodeIndexContext;
import ru.nsu.fit.sberlab.vectorindex.node.index.PartitionIndexManager;

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