package ru.nsu.fit.sberlab.vectorindex.node.service;


import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.lang.IgniteFuture;
import org.apache.ignite.resources.IgniteInstanceResource;
import org.apache.ignite.services.ServiceContext;
import ru.nsu.fit.sberlab.vectorindex.common.ScoredVector;
import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeSearchResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchHit;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchResponse;
import ru.nsu.fit.sberlab.vectorindex.node.compute.nodework.NodeSearchJob;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Node-singleton координатор: per-node callAsync + общий дедлайн.
 * Не ответивший узел пропускается (partial=true), а не вешает запрос.
 */
public class SearchAggregatorServiceImpl implements SearchAggregationService {

    private static final long serialVersionUID = 1L;
    private static final int HYDRATION_BATCH = 256;

    @IgniteInstanceResource
    private transient Ignite ignite;

    private transient IgniteCache<Long, VectorObject> cache;
    private transient long timeoutMs;

    @Override
    public void init(ServiceContext ctx) {
        cache = ignite.cache("vectors");
        if (cache == null) {
            throw new IllegalStateException("Cache 'vectors' is not configured on this node");
        }
        timeoutMs = Long.getLong("vector.search.timeout-ms", 2_000L);
    }

    @Override public void execute(ServiceContext ctx) {}
    @Override public void cancel(ServiceContext ctx) {}

    @Override
    public SearchResponse search(float[] vector, int count) {
        long startNanos = System.nanoTime();

        List<ClusterNode> servers = new ArrayList<>(ignite.cluster().forServers().nodes());

        // Scatter
        List<IgniteFuture<NodeSearchResult>> futures = new ArrayList<>(servers.size());
        for (ClusterNode node : servers) {
            futures.add(ignite.compute(ignite.cluster().forNode(node)).callAsync(new NodeSearchJob(vector, count)));
        }

        // Gather
        long deadline = System.currentTimeMillis() + timeoutMs;
        Map<Long, Double> best = new HashMap<>();
        int responded = 0;
        int activeParts = 0;
        for (IgniteFuture<NodeSearchResult> future : futures) {
            long remaining = deadline - System.currentTimeMillis();
            try {
                NodeSearchResult result = future.get(Math.max(1, remaining), TimeUnit.MILLISECONDS);
                if (result == null) continue;
                responded++;
                activeParts += result.activePartitions;
                if (result.results != null) {
                    for (ScoredVector sv : result.results) {
                        best.merge(sv.id(), sv.distance(), Math::min);   // dedup by id
                    }
                }
            } catch (Exception e) {
                // timeout or node failure - degradation
                System.err.println("[aggregator] node skipped: " + e);
            }
        }

        List<Map.Entry<Long, Double>> ordered = new ArrayList<>(best.entrySet());
        ordered.sort(Map.Entry.comparingByValue());

        SearchResponse resp = new SearchResponse();
        resp.results = hydrate(ordered, count);
        resp.tookMs = (System.nanoTime() - startNanos) / 1_000_000;
        resp.respondedNodes = responded;
        resp.totalNodes = servers.size();
        resp.activePartitions = activeParts;
        resp.totalPartitions = ignite.affinity("vectors").partitions();
        resp.partial = responded < servers.size() || activeParts < resp.totalPartitions;
        return resp;
    }

    private List<SearchHit> hydrate(List<Map.Entry<Long, Double>> ordered, int count) {
        List<SearchHit> hits = new ArrayList<>(Math.min(count, ordered.size()));
        int i = 0;
        // add until got exactly count
        while (i < ordered.size() && hits.size() < count) {
            int end = Math.min(ordered.size(), i + HYDRATION_BATCH);
            Set<Long> ids = new HashSet<>();
            for (int j = i; j < end; j++) ids.add(ordered.get(j).getKey());
            Map<Long, VectorObject> loaded = cache.getAll(ids);
            for (int j = i; j < end && hits.size() < count; j++) {
                Map.Entry<Long, Double> e = ordered.get(j);
                VectorObject obj = loaded.get(e.getKey());
                if (obj == null) continue;
                hits.add(new SearchHit(e.getKey(), e.getValue(), obj.getUrl(), obj.getMetadata()));
            }
            i = end;
        }
        return hits;
    }
}
