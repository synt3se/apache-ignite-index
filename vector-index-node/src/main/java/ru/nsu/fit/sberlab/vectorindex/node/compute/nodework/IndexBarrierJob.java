package ru.nsu.fit.sberlab.vectorindex.node.compute.nodework;

import org.apache.ignite.Ignite;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.resources.IgniteInstanceResource;

import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeBarrierProgress;
import ru.nsu.fit.sberlab.vectorindex.node.index.NodeIndexRegistry;

/** Барьер загрузки на конкретном узле: начало (выдача тикетов) или продвижение. */
public class IndexBarrierJob implements IgniteCallable<NodeBarrierProgress> {
    private static final long serialVersionUID = 1L;

    @IgniteInstanceResource
    private transient Ignite ignite;

    private final boolean begin;
    private final String indexId;
    private final String fingerprint;
    private final String barrierId;
    private final boolean optimize;
    private final long drainTimeoutNanos;

    public IndexBarrierJob(boolean begin, String indexId, String fingerprint, String barrierId, boolean optimize,
                           long drainTimeoutNanos) {
        this.begin = begin;
        this.indexId = indexId;
        this.fingerprint = fingerprint;
        this.barrierId = barrierId;
        this.optimize = optimize;
        this.drainTimeoutNanos = drainTimeoutNanos;
    }

    @Override
    public NodeBarrierProgress call() {
        NodeIndexRegistry registry = NodeIndexRegistry.of(ignite);
        if (registry == null) {
            NodeBarrierProgress progress = new NodeBarrierProgress();
            progress.nodeId = String.valueOf(ignite.cluster().localNode().consistentId());
            progress.error = "index registry is not started on this node";
            return progress;
        }
        return begin
                ? registry.beginBarrier(indexId, fingerprint, barrierId, optimize, drainTimeoutNanos)
                : registry.barrierProgress(indexId, fingerprint, barrierId);
    }
}
