package ru.nsu.fit.sberlab.vectorindex.node.compute.nodework;

import org.apache.ignite.Ignite;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.resources.IgniteInstanceResource;

import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeSearchResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.PartitionFailure;
import ru.nsu.fit.sberlab.vectorindex.node.index.NodeIndexRegistry;
import ru.nsu.fit.sberlab.vectorindex.node.search.NodeSearchRequest;

/**
 * Поиск на узле по назначенным партициям индекса. Бюджет — остаток общего бюджета запроса на
 * момент отправки; узел строит из него свой локальный дедлайн и не начинает поиск по
 * партиции после его исчерпания. Проверки индекса и определения — {@link NodeIndexRegistry#search}.
 */
public class NodeSearchJob implements IgniteCallable<NodeSearchResult> {
    private static final long serialVersionUID = 3L;

    @IgniteInstanceResource
    private transient Ignite ignite;

    private final NodeSearchRequest request;

    public NodeSearchJob(NodeSearchRequest request) {
        this.request = request;
    }

    @Override
    public NodeSearchResult call() {
        NodeIndexRegistry registry = NodeIndexRegistry.of(ignite);
        if (registry == null) {
            return NodeSearchResult.rejected(request.partitions(), PartitionFailure.Reason.NO_INDEX, null);
        }
        return registry.search(request);
    }
}
