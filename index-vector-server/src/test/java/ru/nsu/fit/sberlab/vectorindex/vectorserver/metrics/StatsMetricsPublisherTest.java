package ru.nsu.fit.sberlab.vectorindex.vectorserver.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import ru.nsu.fit.sberlab.vectorindex.common.dto.ClusterStats;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexNodeStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeStats;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.VectorService;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.FakeIndex;

/** Метрики индекса — с тегами node и index; общие ресурсы узла — по одному значению на узел. */
class StatsMetricsPublisherTest {

    @Test
    void indexMetricsAreTaggedByIndexAndSharedNodeMetricsAreNotDuplicated() {
        ClusterStats cluster = new ClusterStats();
        NodeStats node = new NodeStats();
        node.nodeId = "n1";
        node.heapUsedBytes = 1_000;
        node.applierBacklog = 3;
        node.indexes = List.of(index("title", 10, 2), index("body", 20, 0));
        cluster.nodes = List.of(node);
        cluster.serverNodes = 1;
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StatsMetricsPublisher publisher = new StatsMetricsPublisher(new VectorService(index(cluster), null),
                registry, 32);

        publisher.poll();

        assertThat(registry.get("vindex.index.live.vectors").tags("node", "n1", "index", "title").gauge().value())
                .isEqualTo(10);
        assertThat(registry.get("vindex.index.live.vectors").tags("node", "n1", "index", "body").gauge().value())
                .isEqualTo(20);
        assertThat(registry.get("vindex.index.pending.applies").tags("index", "title").gauge().value()).isEqualTo(2);
        assertThat(registry.find("vindex.node.heap.used.bytes").gauges()).as("one per node").hasSize(1);
        assertThat(registry.find("vindex.node.applier.backlog").gauges()).hasSize(1);
        assertThat(registry.find("vindex.index.heap.used.bytes").gauges()).as("not per index").isEmpty();
    }

    private static FakeIndex index(ClusterStats cluster) {
        FakeIndex index = new FakeIndex();
        index.stats = cluster;
        return index;
    }

    private static IndexNodeStatus index(String indexId, long live, long pending) {
        IndexNodeStatus status = new IndexNodeStatus();
        status.indexId = indexId;
        status.state = IndexNodeStatus.State.LOADED;
        status.liveVectors = live;
        status.pendingApplies = pending;
        return status;
    }
}
