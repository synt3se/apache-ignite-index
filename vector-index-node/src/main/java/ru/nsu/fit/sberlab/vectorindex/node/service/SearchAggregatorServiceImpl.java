package ru.nsu.fit.sberlab.vectorindex.node.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.apache.ignite.Ignite;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.resources.IgniteInstanceResource;
import org.apache.ignite.services.ServiceContext;

import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexInfo;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexNodeStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexOperationResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexOperationResult.NodeOutcome;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexOperationResult.Outcome;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchError;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchRequest;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchResponse;
import ru.nsu.fit.sberlab.vectorindex.core.Budget;
import ru.nsu.fit.sberlab.vectorindex.core.NanoClock;
import ru.nsu.fit.sberlab.vectorindex.node.compute.nodework.IndexAdminJob;
import ru.nsu.fit.sberlab.vectorindex.node.compute.nodework.IndexStatusJob;
import ru.nsu.fit.sberlab.vectorindex.node.index.IndexDefinition;
import ru.nsu.fit.sberlab.vectorindex.node.index.NodeIndexRegistry;
import ru.nsu.fit.sberlab.vectorindex.node.search.Awaiter;
import ru.nsu.fit.sberlab.vectorindex.node.search.DistributedSearchResult;
import ru.nsu.fit.sberlab.vectorindex.node.search.IgniteFutures;
import ru.nsu.fit.sberlab.vectorindex.node.search.IgniteNodeTransport;
import ru.nsu.fit.sberlab.vectorindex.node.search.IgniteSearchTopology;
import ru.nsu.fit.sberlab.vectorindex.node.search.NodeQuery;
import ru.nsu.fit.sberlab.vectorindex.node.search.SearchCoordinator;

/**
 * Node-singleton координатор. Определение индекса берётся из реестра этого узла; его отпечаток
 * уходит каждому узлу, и узел с другим определением не попадает в покрытие. Назначение — по
 * affinity кэша индекса. Бюджет — остаток, переданный вызывающим ({@code timeoutMs}), иначе
 * {@code vector.search.timeout-ms}. Документы не читаются: гидрация — в прикладном слое.
 */
public class SearchAggregatorServiceImpl implements SearchAggregationService {

    private static final long serialVersionUID = 2L;
    static final int MAX_K = 10_000;

    @IgniteInstanceResource
    private transient Ignite ignite;

    private transient Map<String, SearchCoordinator> coordinators;
    private transient long defaultTimeoutNanos;
    private transient long adminTimeoutNanos;

    @Override
    public void init(ServiceContext ctx) {
        coordinators = new ConcurrentHashMap<>();
        defaultTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(Long.getLong("vector.search.timeout-ms", 2_000L));
        adminTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(Long.getLong("vector.admin.timeout-ms", 10_000L));
    }

    @Override public void execute(ServiceContext ctx) {}
    @Override public void cancel(ServiceContext ctx) {}

    @Override
    public VectorSearchResponse search(VectorSearchRequest request) {
        long start = NanoClock.SYSTEM.nanoTime();
        if (request == null) {
            return VectorSearchResponse.failed(null, SearchError.INVALID_REQUEST, "request is required");
        }
        IndexDefinition definition = registry().definition(request.indexId);
        if (definition == null) {
            return VectorSearchResponse.failed(request.indexId, SearchError.UNKNOWN_INDEX,
                    "unknown index: " + request.indexId);
        }
        NodeQuery query;
        try {
            query = query(definition, request);
        } catch (IllegalArgumentException invalid) {
            return VectorSearchResponse.failed(request.indexId, SearchError.INVALID_REQUEST, invalid.getMessage());
        }
        long timeout = request.timeoutMs > 0 ? TimeUnit.MILLISECONDS.toNanos(request.timeoutMs) : defaultTimeoutNanos;
        Budget budget = Budget.remaining(NanoClock.SYSTEM, timeout);
        DistributedSearchResult result = coordinators.computeIfAbsent(definition.cacheName(),
                cache -> new SearchCoordinator(new IgniteSearchTopology(ignite, cache), new IgniteNodeTransport(ignite),
                        Awaiter.BLOCKING)).search(query, budget);
        return result.toResponse(definition.indexId(), request.k, request.allowPartial,
                TimeUnit.NANOSECONDS.toMillis(NanoClock.SYSTEM.nanoTime() - start));
    }

    /** Проверка запроса по определению координатора; ошибка — {@link IllegalArgumentException}. */
    static NodeQuery query(IndexDefinition definition, VectorSearchRequest request) {
        if (request.vector == null) {
            throw new IllegalArgumentException("vector is required");
        }
        definition.spec().prepareQuery(request.vector);
        if (request.k <= 0 || request.k > MAX_K) {
            throw new IllegalArgumentException("k must be in 1.." + MAX_K + ": " + request.k);
        }
        definition.searchParams(request.params, request.k);
        return new NodeQuery(definition.indexId(), definition.fingerprint(), request.vector, request.k, request.exact,
                request.params);
    }

    @Override
    public List<IndexInfo> indexes() {
        return registry().indexes();
    }

    @Override
    public IndexStatus status(String indexId) {
        IndexDefinition definition = registry().definition(indexId);
        if (definition == null) {
            return null;
        }
        var snapshot = new IgniteSearchTopology(ignite, definition.cacheName()).snapshot();
        Collection<ClusterNode> servers = ignite.cluster().forServers().nodes();
        Map<String, IndexNodeStatus> statuses = perNode(servers, node -> new IndexStatusJob(indexId), new LinkedHashMap<>());
        return ClusterIndexViews.status(indexId, definition.fingerprint(), snapshot, ids(servers), statuses);
    }

    @Override
    public IndexOperationResult unload(String indexId) {
        return operation(indexId, IndexAdminJob.Operation.UNLOAD, EnumSet.of(Outcome.DONE, Outcome.ALREADY_UNLOADED));
    }

    @Override
    public IndexOperationResult rebuild(String indexId) {
        return operation(indexId, IndexAdminJob.Operation.REBUILD, EnumSet.of(Outcome.DONE));
    }

    private IndexOperationResult operation(String indexId, IndexAdminJob.Operation operation,
                                           Collection<Outcome> accepted) {
        if (registry().definition(indexId) == null) {
            return null;
        }
        Collection<ClusterNode> servers = ignite.cluster().forServers().nodes();
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, NodeOutcome> outcomes = perNode(servers, node -> new IndexAdminJob(operation, indexId), errors);
        errors.forEach((node, error) -> outcomes.put(node, new NodeOutcome(node,
                error.startsWith("timeout") ? Outcome.NO_RESPONSE : Outcome.FAILED, error)));
        return ClusterIndexViews.operation(indexId, operation.name(), ids(servers), outcomes, accepted);
    }

    /** Отдельный вызов на каждый сервер: отказ одного узла не теряет ответы остальных, без failover. */
    private <T> Map<String, T> perNode(Collection<ClusterNode> nodes, Function<ClusterNode, IgniteCallable<T>> job,
                                       Map<String, String> errors) {
        Budget budget = Budget.remaining(NanoClock.SYSTEM, adminTimeoutNanos);
        Map<String, CompletableFuture<T>> futures = new LinkedHashMap<>();
        for (ClusterNode node : nodes) {
            String id = node.id().toString();
            try {
                futures.put(id, IgniteFutures.toCompletable(
                        ignite.compute(ignite.cluster().forNode(node)).callAsync(job.apply(node))));
            } catch (RuntimeException sendFailed) {
                errors.put(id, String.valueOf(sendFailed));
            }
        }
        Map<String, T> results = new LinkedHashMap<>();
        futures.forEach((id, future) -> {
            try {
                T value = future.get(budget.remainingNanos(), TimeUnit.NANOSECONDS);
                if (value != null) {
                    results.put(id, value);
                } else {
                    errors.put(id, "empty response");
                }
            } catch (TimeoutException timeout) {
                future.cancel(true);
                errors.put(id, "timeout");
            } catch (ExecutionException failed) {
                errors.put(id, String.valueOf(failed.getCause()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                errors.put(id, "timeout (interrupted)");
            }
        });
        return results;
    }

    private static List<String> ids(Collection<ClusterNode> nodes) {
        List<String> ids = new ArrayList<>(nodes.size());
        nodes.forEach(node -> ids.add(node.id().toString()));
        return ids;
    }

    private NodeIndexRegistry registry() {
        NodeIndexRegistry registry = NodeIndexRegistry.of(ignite);
        if (registry == null) {
            throw new IllegalStateException("vector index registry is not initialized on this node");
        }
        return registry;
    }
}
