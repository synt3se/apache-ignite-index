package ru.nsu.fit.sberlab.vectorindex.node.compute.nodework;

import org.apache.ignite.Ignite;
import org.apache.ignite.compute.ComputeJobAdapter;
import org.apache.ignite.resources.IgniteInstanceResource;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeStats;
import ru.nsu.fit.sberlab.vectorindex.node.index.NodeIndexRegistry;

/** Статистика узла: общие ресурсы — по разу на узел, индексы — списком (см. {@link NodeIndexRegistry#nodeStats}). */
public class NodeStatsJob extends ComputeJobAdapter {
    @IgniteInstanceResource
    private transient Ignite ignite;

    @Override
    public Object execute() {
        NodeIndexRegistry registry = NodeIndexRegistry.of(ignite);
        if (registry == null) {
            NodeStats s = new NodeStats();
            s.nodeId = "(starting)";
            return s;
        }
        return registry.nodeStats();
    }
}
