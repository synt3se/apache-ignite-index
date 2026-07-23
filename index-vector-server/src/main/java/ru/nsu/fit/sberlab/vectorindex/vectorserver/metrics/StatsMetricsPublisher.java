package ru.nsu.fit.sberlab.vectorindex.vectorserver.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.nsu.fit.sberlab.vectorindex.common.dto.ClusterStats;
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
