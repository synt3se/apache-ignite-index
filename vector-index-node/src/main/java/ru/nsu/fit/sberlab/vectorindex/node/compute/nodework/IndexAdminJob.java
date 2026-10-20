package ru.nsu.fit.sberlab.vectorindex.node.compute.nodework;

import org.apache.ignite.Ignite;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.resources.IgniteInstanceResource;

import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexOperationResult.NodeOutcome;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexOperationResult.Outcome;
import ru.nsu.fit.sberlab.vectorindex.node.index.NodeIndexRegistry;

/** Операция над индексом на конкретном узле; исход возвращается, а не выводится из отсутствия ошибки. */
public class IndexAdminJob implements IgniteCallable<NodeOutcome> {
    private static final long serialVersionUID = 1L;

    public enum Operation { UNLOAD, REBUILD }

    @IgniteInstanceResource
    private transient Ignite ignite;

    private final Operation operation;
    private final String indexId;

    public IndexAdminJob(Operation operation, String indexId) {
        this.operation = operation;
        this.indexId = indexId;
    }

    @Override
    public NodeOutcome call() {
        String node = ignite.cluster().localNode().id().toString();
        NodeIndexRegistry registry = NodeIndexRegistry.of(ignite);
        if (registry == null) {
            return new NodeOutcome(node, Outcome.UNKNOWN_INDEX, "index registry is not started on this node");
        }
        try {
            Outcome outcome = operation == Operation.UNLOAD ? registry.unload(indexId) : registry.rebuild(indexId);
            return new NodeOutcome(node, outcome, null);
        } catch (RuntimeException e) {
            return new NodeOutcome(node, Outcome.FAILED, e.getMessage());
        }
    }
}
