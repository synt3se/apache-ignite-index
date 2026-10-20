package ru.nsu.fit.sberlab.vectorindex.node.compute.nodework;

import org.apache.ignite.Ignite;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.resources.IgniteInstanceResource;

import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexNodeStatus;
import ru.nsu.fit.sberlab.vectorindex.node.index.NodeIndexRegistry;

/** Готовность индекса на конкретном узле (координатор опрашивает каждый сервер отдельно). */
public class IndexStatusJob implements IgniteCallable<IndexNodeStatus> {
    private static final long serialVersionUID = 1L;

    @IgniteInstanceResource
    private transient Ignite ignite;

    private final String indexId;

    public IndexStatusJob(String indexId) {
        this.indexId = indexId;
    }

    @Override
    public IndexNodeStatus call() {
        NodeIndexRegistry registry = NodeIndexRegistry.of(ignite);
        if (registry == null) {
            IndexNodeStatus status = new IndexNodeStatus();
            status.nodeId = String.valueOf(ignite.cluster().localNode().consistentId());
            status.indexId = indexId;
            status.state = IndexNodeStatus.State.UNKNOWN_INDEX;
            return status;
        }
        return registry.status(indexId);
    }
}
