package ru.nsu.fit.sberlab.vectorindex.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteCluster;
import org.apache.ignite.IgniteCompute;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cache.affinity.Affinity;
import org.apache.ignite.cluster.ClusterGroup;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.lang.IgniteFuture;
import org.apache.ignite.lang.IgniteFutureTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import ru.nsu.fit.sberlab.vectorindex.common.ScoredVector;
import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeSearchResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchResponse;
import ru.nsu.fit.sberlab.vectorindex.node.compute.nodework.NodeSearchJob;

/** Distributed search semantics with no Ignite nodes, discovery, or network IO. */
@ResourceLock("vector.search.timeout-ms")
class SearchAggregatorServiceImplTest {
    private final Ignite ignite = mock(Ignite.class);
    private final IgniteCluster cluster = mock(IgniteCluster.class);
    private final ClusterGroup serversGroup = mock(ClusterGroup.class);
    @SuppressWarnings("unchecked")
    private final IgniteCache<Long, VectorObject> cache = mock(IgniteCache.class);
    @SuppressWarnings("unchecked")
    private final Affinity<Long> affinity = mock(Affinity.class);
    private final List<ClusterNode> servers = new ArrayList<>();
    private final Map<Long, VectorObject> records = new HashMap<>();
    private SearchAggregatorServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        when(ignite.cluster()).thenReturn(cluster);
        when(cluster.forServers()).thenReturn(serversGroup);
        when(serversGroup.nodes()).thenAnswer(invocation -> new ArrayList<>(servers));
        when(ignite.<Long, VectorObject>cache("vectors")).thenReturn(cache);
        when(ignite.<Long>affinity("vectors")).thenReturn(affinity);
        when(affinity.partitions()).thenReturn(4);
        // Reverse key order deliberately: getAll does not promise distance order.
        when(cache.getAll(anySet())).thenAnswer(invocation -> {
            Set<Long> ids = invocation.getArgument(0);
            Map<Long, VectorObject> loaded = new LinkedHashMap<>();
            ids.stream().sorted(java.util.Comparator.reverseOrder()).forEach(id -> {
                if (records.containsKey(id)) loaded.put(id, records.get(id));
            });
            return loaded;
        });
        service = createService(2_000);
    }

    @Test
    @DisplayName("Global top-k merges all nodes by ascending distance")
    void mergesGlobalTopK() {
        node(2, candidate(1, 0.8), candidate(3, 0.3));
        node(2, candidate(2, 0.2), candidate(4, 0.1));
        store(1, 2, 3, 4);

        SearchResponse response = service.search(new float[]{1, 0}, 3);

        assertThat(response.results).extracting(hit -> hit.id).containsExactly(4L, 2L, 3L);
        assertThat(response.results).extracting(hit -> hit.distance).containsExactly(0.1, 0.2, 0.3);
        assertThat(response.partial).isFalse();
        assertThat(response.respondedNodes).isEqualTo(2);
        assertThat(response.totalNodes).isEqualTo(2);
        assertThat(response.activePartitions).isEqualTo(4);
        assertThat(response.totalPartitions).isEqualTo(4);
    }

    @Test
    @DisplayName("Duplicate IDs use their best score and occupy one result slot")
    void deduplicatesCandidatesWithinAndAcrossNodes() {
        node(2, candidate(7, 0.9), candidate(7, 0.4));
        node(2, candidate(7, 0.1), candidate(8, 0.2));
        store(7, 8);

        SearchResponse response = service.search(new float[]{1, 0}, 3);

        assertThat(response.results).extracting(hit -> hit.id).containsExactly(7L, 8L);
        assertThat(response.results).extracting(hit -> hit.distance).containsExactly(0.1, 0.2);
    }

    @Test
    @DisplayName("Hydration preserves score order and attaches the corresponding record")
    void hydrationKeepsSearchOrderAndRecordMetadata() {
        node(4, candidate(1, 0.1), candidate(2, 0.2), candidate(3, 0.3));
        store(1, 2, 3);

        SearchResponse response = service.search(new float[]{1, 0}, 3);

        assertThat(response.results).extracting(hit -> hit.id).containsExactly(1L, 2L, 3L);
        assertThat(response.results).extracting(hit -> hit.url)
                .containsExactly("https://vectors/1", "https://vectors/2", "https://vectors/3");
        assertThat(response.results).extracting(hit -> hit.metadata)
                .containsExactly("metadata-1", "metadata-2", "metadata-3");
    }

    @Test
    @DisplayName("Deleted candidates are skipped and later hydration batches backfill top-k")
    void backfillsAfterDeletedCandidatesAcrossHydrationBatches() {
        ScoredVector[] candidates = IntStream.range(0, 300)
                .mapToObj(id -> candidate(id, id / 300.0)).toArray(ScoredVector[]::new);
        node(4, candidates);
        // Every candidate in the first batch was deleted before hydration.
        store(270, 299);

        SearchResponse response = service.search(new float[]{1, 0}, 2);

        assertThat(response.results).extracting(hit -> hit.id).containsExactly(270L, 299L);
        verify(cache, times(2)).getAll(anySet());
        assertThat(response.partial).isFalse();
    }

    @Test
    @DisplayName("Fewer live candidates than k yields a shorter complete answer")
    void returnsOnlyLiveRecordsWhenBackfillIsExhausted() {
        node(4, candidate(1, 0.1), candidate(2, 0.2), candidate(3, 0.3));
        store(2);

        SearchResponse response = service.search(new float[]{1, 0}, 10);

        assertThat(response.results).extracting(hit -> hit.id).containsExactly(2L);
        assertThat(response.partial).isFalse();
    }

    @Test
    @DisplayName("A failed node does not discard healthy results and makes the answer partial")
    void degradesOnNodeFailure() {
        node(2, candidate(1, 0.1));
        IgniteFuture<NodeSearchResult> failed = futureNode();
        when(failed.get(anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new IgniteException("simulated node failure"));
        store(1);

        SearchResponse response = service.search(new float[]{1, 0}, 1);

        assertThat(response.results).extracting(hit -> hit.id).containsExactly(1L);
        assertThat(response.partial).isTrue();
        assertThat(response.respondedNodes).isEqualTo(1);
        assertThat(response.totalNodes).isEqualTo(2);
        assertThat(response.activePartitions).isEqualTo(2);
    }

    @Test
    @DisplayName("A timed-out node does not prevent collecting subsequent healthy futures")
    void continuesGatheringAfterTimeout() {
        IgniteFuture<NodeSearchResult> timedOut = futureNode();
        when(timedOut.get(anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new IgniteFutureTimeoutException("simulated timeout"));
        node(2, candidate(1, 0.1));
        store(1);

        SearchResponse response = service.search(new float[]{1, 0}, 1);

        assertThat(response.results).extracting(hit -> hit.id).containsExactly(1L);
        assertThat(response.partial).isTrue();
        assertThat(response.respondedNodes).isEqualTo(1);
    }

    @Test
    @DisplayName("All nodes responding does not imply complete partition coverage")
    void marksMissingPartitionsAsPartialEvenWhenTopKIsFull() {
        node(1, candidate(1, 0.1));
        node(1, candidate(2, 0.2));
        store(1, 2);

        SearchResponse response = service.search(new float[]{1, 0}, 2);

        assertThat(response.results).hasSize(2);
        assertThat(response.respondedNodes).isEqualTo(response.totalNodes).isEqualTo(2);
        assertThat(response.activePartitions).isEqualTo(2);
        assertThat(response.totalPartitions).isEqualTo(4);
        assertThat(response.partial).isTrue();
    }

    @Test
    @DisplayName("A null node response counts as unavailable, not as an empty successful shard")
    void treatsNullNodeResultAsUnavailable() {
        node(2, candidate(1, 0.1));
        futureNode(); // Mockito returns null from get.
        store(1);

        SearchResponse response = service.search(new float[]{1, 0}, 1);

        assertThat(response.results).extracting(hit -> hit.id).containsExactly(1L);
        assertThat(response.respondedNodes).isEqualTo(1);
        assertThat(response.partial).isTrue();
    }

    @Test
    @DisplayName("An empty fully searched index is complete and needs no hydration")
    void completeEmptyIndexDoesNotHydrate() {
        node(4);

        SearchResponse response = service.search(new float[]{1, 0}, 10);

        assertThat(response.results).isEmpty();
        assertThat(response.partial).isFalse();
        assertThat(response.activePartitions).isEqualTo(4);
        verify(cache, never()).getAll(anySet());
    }

    @Test
    @DisplayName("Without server nodes the result is empty and partition coverage is partial")
    void noServersMeansNoPartitionCoverage() {
        SearchResponse response = service.search(new float[]{1, 0}, 10);

        assertThat(response.results).isEmpty();
        assertThat(response.totalNodes).isZero();
        assertThat(response.respondedNodes).isZero();
        assertThat(response.activePartitions).isZero();
        assertThat(response.partial).isTrue();
        verify(cache, never()).getAll(anySet());
    }

    @Test
    @Tag("known-issue")
    @DisplayName("К4: hydration must not start after gathering exhausts the request deadline")
    void doesNotStartHydrationAfterDeadlineIsExhausted() throws Exception {
        service = createService(100);
        node(2, candidate(1, 0.1));
        IgniteFuture<NodeSearchResult> timedOut = futureNode();
        when(timedOut.get(anyLong(), eq(TimeUnit.MILLISECONDS))).thenAnswer(invocation -> {
            // A real future waits out the supplied budget before reporting timeout.
            // The margin crosses the millisecond deadline without a timing assertion.
            long waitMs = invocation.getArgument(0);
            TimeUnit.MILLISECONDS.sleep(waitMs + 20);
            throw new IgniteFutureTimeoutException("gather consumed the remaining request budget");
        });
        store(1);

        SearchResponse response = service.search(new float[]{1, 0}, 1);

        verify(cache, never()).getAll(anySet());
        assertThat(response.results).isEmpty();
        assertThat(response.partial).isTrue();
    }

    private SearchAggregatorServiceImpl createService(long timeoutMs) throws Exception {
        SearchAggregatorServiceImpl result = new SearchAggregatorServiceImpl();
        Field field = SearchAggregatorServiceImpl.class.getDeclaredField("ignite");
        field.setAccessible(true);
        field.set(result, ignite);
        String previous = System.getProperty("vector.search.timeout-ms");
        try {
            System.setProperty("vector.search.timeout-ms", Long.toString(timeoutMs));
            result.init(null);
        } finally {
            if (previous == null) System.clearProperty("vector.search.timeout-ms");
            else System.setProperty("vector.search.timeout-ms", previous);
        }
        return result;
    }

    private void node(int activePartitions, ScoredVector... candidates) {
        IgniteFuture<NodeSearchResult> future = futureNode();
        when(future.get(anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(new NodeSearchResult(List.of(candidates), activePartitions));
    }

    @SuppressWarnings("unchecked")
    private IgniteFuture<NodeSearchResult> futureNode() {
        ClusterNode node = mock(ClusterNode.class);
        ClusterGroup group = mock(ClusterGroup.class);
        IgniteCompute compute = mock(IgniteCompute.class);
        IgniteFuture<NodeSearchResult> future = mock(IgniteFuture.class);
        servers.add(node);
        when(cluster.forNode(node)).thenReturn(group);
        when(ignite.compute(group)).thenReturn(compute);
        when(compute.<NodeSearchResult>callAsync(any(NodeSearchJob.class))).thenReturn(future);
        return future;
    }

    private void store(long... ids) {
        for (long id : ids) {
            records.put(id, new VectorObject(new float[]{1, 0},
                    "https://vectors/" + id, "metadata-" + id));
        }
    }

    private static ScoredVector candidate(long id, double distance) {
        return new ScoredVector(id, distance);
    }
}
