package ru.nsu.fit.sberlab.vectorindex.node.compute.nodework;

import java.util.List;

import org.apache.ignite.Ignite;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.resources.IgniteInstanceResource;

import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeSearchResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.PartitionFailure;
import ru.nsu.fit.sberlab.vectorindex.core.Budget;
import ru.nsu.fit.sberlab.vectorindex.core.NanoClock;
import ru.nsu.fit.sberlab.vectorindex.node.index.IgniteIndexAdapter;
import ru.nsu.fit.sberlab.vectorindex.node.index.NodeIndexContext;
import ru.nsu.fit.sberlab.vectorindex.node.search.NodeSearchRequest;

/**
 * Поиск на узле по назначенным партициям. Бюджет — остаток общего бюджета запроса на
 * момент отправки; узел строит из него свой локальный дедлайн и не начинает поиск по
 * партиции после его исчерпания.
 */
public class NodeSearchJob implements IgniteCallable<NodeSearchResult> {
    private static final long serialVersionUID = 2L;

    @IgniteInstanceResource
    private transient Ignite ignite;

    private final NodeSearchRequest request;

    public NodeSearchJob(NodeSearchRequest request) {
        this.request = request;
    }

    @Override
    public NodeSearchResult call() {
        IgniteIndexAdapter adapter = NodeIndexContext.manager(ignite);
        if (adapter == null) {
            return NodeSearchResult.rejected(request.partitions(), PartitionFailure.Reason.NO_INDEX, null);
        }
        Budget budget = Budget.remaining(NanoClock.SYSTEM, request.budgetNanos());
        return adapter.search(request.partitions(), request.query(), request.k(), null, budget);
    }
}
