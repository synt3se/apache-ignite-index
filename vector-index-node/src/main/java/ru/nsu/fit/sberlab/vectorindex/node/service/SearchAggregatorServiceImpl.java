package ru.nsu.fit.sberlab.vectorindex.node.service;


import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.resources.IgniteInstanceResource;
import org.apache.ignite.services.ServiceContext;
import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchResponse;
import ru.nsu.fit.sberlab.vectorindex.core.Budget;
import ru.nsu.fit.sberlab.vectorindex.core.NanoClock;
import ru.nsu.fit.sberlab.vectorindex.node.index.IndexDefinition;
import ru.nsu.fit.sberlab.vectorindex.node.index.NodeIndexRegistry;
import ru.nsu.fit.sberlab.vectorindex.node.search.Awaiter;
import ru.nsu.fit.sberlab.vectorindex.node.search.DemoHydrator;
import ru.nsu.fit.sberlab.vectorindex.node.search.DistributedSearchResult;
import ru.nsu.fit.sberlab.vectorindex.node.search.IgniteDocumentStore;
import ru.nsu.fit.sberlab.vectorindex.node.search.IgniteNodeTransport;
import ru.nsu.fit.sberlab.vectorindex.node.search.IgniteSearchTopology;
import ru.nsu.fit.sberlab.vectorindex.node.search.NodeQuery;
import ru.nsu.fit.sberlab.vectorindex.node.search.SearchCoordinator;

/**
 * Node-singleton координатор: один бюджет на весь запрос (рассылка, сбор и гидрация),
 * покрытие — множество партиций, обработанных назначенными узлами. Не ответивший узел
 * делает ответ partial, а не вешает запрос. Логика — {@link SearchCoordinator} и
 * {@link DemoHydrator}; здесь только связывание с Ignite.
 */
public class SearchAggregatorServiceImpl implements SearchAggregationService {

    private static final long serialVersionUID = 1L;
    private static final String CACHE = "vectors";

    @IgniteInstanceResource
    private transient Ignite ignite;

    private transient SearchCoordinator coordinator;
    private transient DemoHydrator hydrator;
    private transient long timeoutNanos;

    @Override
    public void init(ServiceContext ctx) {
        IgniteCache<Long, VectorObject> cache = ignite.cache(CACHE);
        if (cache == null) {
            throw new IllegalStateException("Cache '" + CACHE + "' is not configured on this node");
        }
        timeoutNanos = TimeUnit.MILLISECONDS.toNanos(Long.getLong("vector.search.timeout-ms", 2_000L));
        coordinator = new SearchCoordinator(new IgniteSearchTopology(ignite, CACHE), new IgniteNodeTransport(ignite),
                Awaiter.BLOCKING);
        hydrator = new DemoHydrator(new IgniteDocumentStore(cache), Awaiter.BLOCKING, NanoClock.SYSTEM);
    }

    @Override public void execute(ServiceContext ctx) {}
    @Override public void cancel(ServiceContext ctx) {}

    @Override
    public SearchResponse search(float[] vector, int count) {
        long start = NanoClock.SYSTEM.nanoTime();
        Budget budget = Budget.remaining(NanoClock.SYSTEM, timeoutNanos);
        NodeIndexRegistry registry = NodeIndexRegistry.of(ignite);
        if (registry == null) {
            throw new IllegalStateException("vector index registry is not initialized on this node");
        }
        IndexDefinition definition = registry.definition(registry.defaultIndexId());
        definition.spec().prepareQuery(vector);
        DistributedSearchResult result = coordinator.search(new NodeQuery(definition.indexId(),
                definition.fingerprint(), vector, count, false, Map.of()), budget);
        return hydrator.respond(result, count, budget, start);
    }
}
