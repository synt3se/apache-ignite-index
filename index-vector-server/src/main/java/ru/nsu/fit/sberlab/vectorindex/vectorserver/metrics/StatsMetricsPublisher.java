package ru.nsu.fit.sberlab.vectorindex.vectorserver.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.nsu.fit.sberlab.vectorindex.common.dto.ClusterStats;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexNodeStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeStats;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.VectorService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Опрашивает /vectors/stats и публикует значения как Micrometer-гейджи для Prometheus. */
@Component
@ConditionalOnProperty(name = "vindex.metrics.enabled", havingValue = "true", matchIfMissing = true)
public class StatsMetricsPublisher {

    private final VectorService vectorService;
    private final MeterRegistry registry;
    private final int totalPartitions;

    private final AtomicReference<ClusterStats> latest = new AtomicReference<>(new ClusterStats());
    private final Map<String, AtomicReference<NodeStats>> perNode = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<IndexNodeStatus>> perIndex = new ConcurrentHashMap<>();
    private final AtomicInteger reachable = new AtomicInteger(0);

    public StatsMetricsPublisher(VectorService vectorService,
                                 MeterRegistry registry,
                                 @Value("${vindex.total-partitions:32}") int totalPartitions) {
        this.vectorService = vectorService;
        this.registry = registry;
        this.totalPartitions = totalPartitions;

        Gauge.builder("vindex.cluster.reachable", reachable, AtomicInteger::get)
                .description("1 если stats опросился, иначе 0").register(registry);
        Gauge.builder("vindex.cluster.server.nodes", latest, r -> r.get().serverNodes).register(registry);
        Gauge.builder("vindex.cluster.live.vectors", latest, r -> r.get().totalLiveVectors).register(registry);
        Gauge.builder("vindex.cluster.index.memory.bytes", latest, r -> r.get().totalIndexMemoryEstimateBytes).register(registry);
        Gauge.builder("vindex.cluster.active.partitions", latest, r -> sumActive(r.get())).register(registry);
        Gauge.builder("vindex.cluster.total.partitions", this, s -> s.totalPartitions).register(registry);
        Gauge.builder("vindex.cluster.coverage.ratio", latest, r -> coverage(r.get()))
                .description("active / total партиций (0..1)").register(registry);
    }

    @Scheduled(fixedRateString = "${vindex.metrics.poll-ms:2000}")
    public void poll() {
        try {
            ClusterStats cs = vectorService.stats().getBody();
            if (cs == null) { reachable.set(0); return; }
            latest.set(cs);
            reachable.set(1);
            if (cs.nodes != null) {
                for (NodeStats n : cs.nodes) {
                    perNode.computeIfAbsent(n.nodeId, this::registerNode).set(n);
                    if (n.indexes != null) {
                        for (IndexNodeStatus index : n.indexes) {
                            perIndex.computeIfAbsent(n.nodeId + "|" + index.indexId,
                                    key -> registerIndex(n.nodeId, index.indexId)).set(index);
                        }
                    }
                }
            }
        } catch (Exception e) {
            reachable.set(0);   // кластер недоступен — держим последние значения, coverage упадёт
        }
    }

    private AtomicReference<NodeStats> registerNode(String nodeId) {
        AtomicReference<NodeStats> ref = new AtomicReference<>(new NodeStats());
        Tags t = Tags.of("node", nodeId);
        Gauge.builder("vindex.node.owned.partitions",  ref, r -> r.get().ownedPartitions).tags(t).register(registry);
        Gauge.builder("vindex.node.active.partitions", ref, r -> r.get().activePartitions).tags(t).register(registry);
        Gauge.builder("vindex.node.live.vectors",      ref, r -> r.get().liveVectors).tags(t).register(registry);
        Gauge.builder("vindex.node.applier.backlog",   ref, r -> r.get().applierBacklog).tags(t).register(registry);
        Gauge.builder("vindex.node.dirty.partitions",  ref, r -> r.get().dirtyPartitions).tags(t).register(registry);
        Gauge.builder("vindex.node.engine.pending",    ref, r -> r.get().enginePendingVectors).tags(t).register(registry);
        Gauge.builder("vindex.node.heap.used.bytes",   ref, r -> r.get().heapUsedBytes).tags(t).register(registry);
        Gauge.builder("vindex.node.heap.max.bytes",    ref, r -> r.get().heapMaxBytes).tags(t).register(registry);
        Gauge.builder("vindex.node.uptime.ms",         ref, r -> r.get().uptimeMs).tags(t).register(registry);
        return ref;
    }

    /**
     * Метрики индекса на узле (§7.8), теги {@code node} и {@code index}. Общие ресурсы узла
     * (heap, общая очередь применения) здесь не повторяются — они в {@code vindex.node.*}.
     */
    private AtomicReference<IndexNodeStatus> registerIndex(String nodeId, String indexId) {
        AtomicReference<IndexNodeStatus> ref = new AtomicReference<>(new IndexNodeStatus());
        Tags t = Tags.of("node", nodeId, "index", indexId);
        index("vindex.index.loaded", ref, t, s -> s.state == IndexNodeStatus.State.LOADED ? 1 : 0);
        index("vindex.index.owned.partitions", ref, t, s -> length(s.ownedPartitions));
        index("vindex.index.ready.partitions", ref, t, s -> length(s.readyPartitions));
        index("vindex.index.loading.partitions", ref, t, s -> length(s.loadingPartitions));
        index("vindex.index.repair.pending.partitions", ref, t, s -> length(s.repairPendingPartitions));
        index("vindex.index.repair.pending.age.ms", ref, t, s -> s.maxRepairPendingAgeMs);
        index("vindex.index.optimization.failed.partitions", ref, t, s -> s.optimizationFailed);
        index("vindex.index.live.vectors", ref, t, s -> s.liveVectors);
        index("vindex.index.delta.entries", ref, t, s -> s.deltaEntries);
        index("vindex.index.frozen.layers", ref, t, s -> s.frozenLayers);
        index("vindex.index.pending.applies", ref, t, s -> s.pendingApplies);
        index("vindex.index.scans.in.progress", ref, t, s -> s.scansInProgress);
        index("vindex.index.applied.total", ref, t, s -> s.appliedTotal);
        index("vindex.index.apply.latency.max.ns", ref, t, s -> s.applyLatencyNanosMax);
        index("vindex.index.writer.lock.wait.max.ns", ref, t, s -> s.writerLockWaitNanosMax);
        index("vindex.index.build.duration.max.ns", ref, t, s -> s.lastBuildDurationNanosMax);
        index("vindex.index.publish.latency.max.ns", ref, t, s -> s.lastPublishLatencyNanosMax);
        index("vindex.index.build.failures.total", ref, t, s -> s.buildFailuresTotal);
        index("vindex.index.publish.rejected.total", ref, t, s -> s.publishRejectedTotal);
        index("vindex.index.rejected.vectors.total", ref, t, s -> s.rejectedVectorsTotal);
        index("vindex.index.wrong.type.values.total", ref, t, s -> s.wrongTypeValuesTotal);
        index("vindex.index.unsupported.keys.total", ref, t, s -> s.unsupportedKeysTotal);
        index("vindex.index.applier.overflows.total", ref, t, s -> s.applierOverflowsTotal);
        index("vindex.index.apply.errors.total", ref, t, s -> s.applyErrorsTotal);
        index("vindex.index.memory.estimate.bytes", ref, t, s -> s.indexMemoryEstimateBytes);
        return ref;
    }

    private void index(String name, AtomicReference<IndexNodeStatus> ref, Tags tags,
                       java.util.function.ToDoubleFunction<IndexNodeStatus> value) {
        Gauge.builder(name, ref, r -> value.applyAsDouble(r.get())).tags(tags).register(registry);
    }

    private static double length(int[] values) {
        return values == null ? 0 : values.length;
    }

    private double sumActive(ClusterStats cs) {
        if (cs == null || cs.nodes == null) return 0;
        int a = 0;
        for (NodeStats n : cs.nodes) a += n.activePartitions;
        return a;
    }

    private double coverage(ClusterStats cs) {
        return totalPartitions <= 0 ? 0 : sumActive(cs) / totalPartitions;
    }
}
