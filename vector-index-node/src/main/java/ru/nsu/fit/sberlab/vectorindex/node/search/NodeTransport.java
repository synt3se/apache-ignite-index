package ru.nsu.fit.sberlab.vectorindex.node.search;

import java.util.concurrent.CompletableFuture;

import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeSearchResult;

/** Асинхронная отправка поиска узлу; Ignite-реализация — compute job на конкретный узел. */
public interface NodeTransport {
    CompletableFuture<NodeSearchResult> search(String nodeId, NodeSearchRequest request);
}
