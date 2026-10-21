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

    /** {@code SUSPEND}/{@code RESUME} — фазы {@code truncate} вокруг очистки кэша. */
    public enum Operation { UNLOAD, REBUILD, SUSPEND, RESUME }

    /** Страховка паузы {@code truncate}: если RESUME не придёт, пауза истечёт сама. */
    static final long TRUNCATE_PAUSE_TTL_MS = 10 * 60_000;

    @IgniteInstanceResource
    private transient Ignite ignite;

    private final Operation operation;
    private final String indexId;
    private final long timeoutNanos;

    public IndexAdminJob(Operation operation, String indexId) {
        this(operation, indexId, 0);
    }

    public IndexAdminJob(Operation operation, String indexId, long timeoutNanos) {
        this.operation = operation;
        this.indexId = indexId;
        this.timeoutNanos = timeoutNanos;
    }

    @Override
    public NodeOutcome call() {
        String node = ignite.cluster().localNode().id().toString();
        NodeIndexRegistry registry = NodeIndexRegistry.of(ignite);
        if (registry == null) {
            return new NodeOutcome(node, Outcome.UNKNOWN_INDEX, "index registry is not started on this node");
        }
        try {
            Outcome outcome = switch (operation) {
                case UNLOAD -> registry.unload(indexId);
                case REBUILD -> registry.rebuild(indexId);
                case SUSPEND -> registry.suspendForTruncate(indexId, TRUNCATE_PAUSE_TTL_MS, timeoutNanos);
                case RESUME -> registry.resumeAfterTruncate(indexId);
            };
            return new NodeOutcome(node, outcome, null);
        } catch (RuntimeException e) {
            return new NodeOutcome(node, Outcome.FAILED, e.getMessage());
        }
    }
}
