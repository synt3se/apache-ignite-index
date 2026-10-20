package ru.nsu.fit.sberlab.vectorindex.node.search;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.ignite.Ignite;

import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeSearchResult;
import ru.nsu.fit.sberlab.vectorindex.node.compute.nodework.NodeSearchJob;

/** Поиск на конкретном узле через compute job; узел ищет только по переданным партициям. */
public final class IgniteNodeTransport implements NodeTransport {

    private final Ignite ignite;

    public IgniteNodeTransport(Ignite ignite) {
        this.ignite = ignite;
    }

    @Override
    public CompletableFuture<NodeSearchResult> search(String nodeId, NodeSearchRequest request) {
        return IgniteFutures.toCompletable(ignite.compute(ignite.cluster().forNodeId(UUID.fromString(nodeId)))
                .callAsync(new NodeSearchJob(request)));
    }
}
