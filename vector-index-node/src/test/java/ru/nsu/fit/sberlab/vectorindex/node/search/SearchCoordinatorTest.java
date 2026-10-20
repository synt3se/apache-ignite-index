package ru.nsu.fit.sberlab.vectorindex.node.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.nsu.fit.sberlab.vectorindex.common.ScoredVector;
import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeSearchResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.PartitionFailure;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchResponse;
import ru.nsu.fit.sberlab.vectorindex.core.Budget;
import ru.nsu.fit.sberlab.vectorindex.testkit.ManualClock;

/**
 * Распределённый поиск без узлов Ignite и сети: назначение, транспорт, документы и часы —
 * подмены, ожидание управляется временем теста (без sleep). Перенос {@code SearchAggregatorServiceImplTest}.
 */
class SearchCoordinatorTest {
    private final ManualClock clock = new ManualClock();
    private final Map<String, int[]> assignment = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<NodeSearchResult>> responses = new HashMap<>();
    private final Map<String, Duration> slowNodes = new HashMap<>();
    private final java.util.Set<String> instantTimeouts = new java.util.HashSet<>();
    private final Map<String, NodeSearchRequest> requests = new HashMap<>();
    private final Map<Long, VectorObject> records = new HashMap<>();
    private final AtomicInteger getAllCalls = new AtomicInteger();
    private int partitions = 4;
    private long budgetMs = 2_000;

    /**
     * Незавершённый ответ «ждёт» весь переданный остаток бюджета (и задержку узла сверх него),
     * затем таймаут; узлы из {@code instantTimeouts} сообщают таймаут сразу.
     */
    private final Awaiter awaiter = new Awaiter() {
        @Override
        public <T> T await(CompletableFuture<T> future, long timeoutNanos)
                throws ExecutionException, TimeoutException, InterruptedException {
            if (future.isDone()) {
                return future.get();
            }
            String node = responses.entrySet().stream().filter(e -> e.getValue() == future)
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (!instantTimeouts.contains(node)) {
                clock.advanceNanos(timeoutNanos + slowNodes.getOrDefault(node, Duration.ZERO).toNanos());
            }
            throw new TimeoutException("simulated timeout");
        }
    };

    private SearchCoordinator coordinator;
    private DemoHydrator hydrator;

    @BeforeEach
    void setUp() {
        SearchTopology topology = () -> new SearchTopology.Snapshot(partitions, assignment, 1);
        NodeTransport transport = (node, request) -> {
            requests.put(node, request);
            return responses.get(node);
        };
        coordinator = new SearchCoordinator(topology, transport, awaiter);
        DocumentStore documents = ids -> {
            getAllCalls.incrementAndGet();
            // Reverse key order deliberately: getAll does not promise distance order.
            Map<Long, VectorObject> loaded = new LinkedHashMap<>();
            ids.stream().sorted(java.util.Comparator.reverseOrder()).forEach(id -> {
                if (records.containsKey(id)) loaded.put(id, records.get(id));
            });
            return CompletableFuture.completedFuture(loaded);
        };
        hydrator = new DemoHydrator(documents, awaiter, clock);
    }

    @Test
    @DisplayName("Global top-k merges all nodes by ascending distance")
    void mergesGlobalTopK() {
        node(parts(0, 1), candidate(1, 0.8), candidate(3, 0.3));
        node(parts(2, 3), candidate(2, 0.2), candidate(4, 0.1));
        store(1, 2, 3, 4);

        SearchResponse response = search(3);

        assertThat(response.results).extracting(hit -> hit.id).containsExactly(4L, 2L, 3L);
        assertThat(response.results).extracting(hit -> hit.distance).containsExactly(0.1, 0.2, 0.3);
        assertThat(response.partial).isFalse();
        assertThat(response.respondedNodes).isEqualTo(2);
        assertThat(response.totalNodes).isEqualTo(2);
        assertThat(response.activePartitions).isEqualTo(4);
        assertThat(response.totalPartitions).isEqualTo(4);
        assertThat(response.searchedPartitions).containsExactly(0, 1, 2, 3);
    }

    @Test
    @DisplayName("Duplicate IDs use their best score, occupy one result slot and are reported")
    void deduplicatesCandidatesWithinAndAcrossNodes() {
        node(parts(0, 1), candidate(7, 0.9), candidate(7, 0.4));
        node(parts(2, 3), candidate(7, 0.1), candidate(8, 0.2));
        store(7, 8);

        SearchResponse response = search(3);

        assertThat(response.results).extracting(hit -> hit.id).containsExactly(7L, 8L);
        assertThat(response.results).extracting(hit -> hit.distance).containsExactly(0.1, 0.2);
        assertThat(response.duplicateKeys).as("assignment violation is visible, not silently resolved").isEqualTo(2);
    }

    @Test
    @DisplayName("Hydration preserves score order and attaches the corresponding record")
    void hydrationKeepsSearchOrderAndRecordMetadata() {
        node(parts(0, 1, 2, 3), candidate(1, 0.1), candidate(2, 0.2), candidate(3, 0.3));
        store(1, 2, 3);

        SearchResponse response = search(3);

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
        node(parts(0, 1, 2, 3), candidates);
        // Every candidate in the first batch was deleted before hydration.
        store(270, 299);

        SearchResponse response = search(2);

        assertThat(response.results).extracting(hit -> hit.id).containsExactly(270L, 299L);
        assertThat(getAllCalls).hasValue(2);
        assertThat(response.partial).isFalse();
    }

    @Test
    @DisplayName("Fewer live candidates than k yields a shorter complete answer")
    void returnsOnlyLiveRecordsWhenBackfillIsExhausted() {
        node(parts(0, 1, 2, 3), candidate(1, 0.1), candidate(2, 0.2), candidate(3, 0.3));
        store(2);

        SearchResponse response = search(10);

        assertThat(response.results).extracting(hit -> hit.id).containsExactly(2L);
        assertThat(response.partial).isFalse();
    }

    @Test
    @DisplayName("A failed node does not discard healthy results and makes the answer partial")
    void degradesOnNodeFailure() {
        node(parts(0, 1), candidate(1, 0.1));
        failedNode(parts(2, 3), new IllegalStateException("simulated node failure"));
        store(1);

        SearchResponse response = search(1);

        assertThat(response.results).extracting(hit -> hit.id).containsExactly(1L);
        assertThat(response.partial).isTrue();
        assertThat(response.respondedNodes).isEqualTo(1);
        assertThat(response.totalNodes).isEqualTo(2);
        assertThat(response.activePartitions).isEqualTo(2);
        assertThat(response.failedPartitions).extracting(f -> f.partition, f -> f.reason).containsExactly(
                tuple(2, PartitionFailure.Reason.NODE_FAILED), tuple(3, PartitionFailure.Reason.NODE_FAILED));
    }

    @Test
    @DisplayName("A timed-out node does not prevent collecting subsequent healthy futures")
    void continuesGatheringAfterTimeout() {
        slowNode(parts(0, 1), Duration.ZERO);
        instantTimeouts.add("node-0");
        node(parts(2, 3), candidate(1, 0.1));
        store(1);

        SearchResponse response = search(1);

        assertThat(response.results).extracting(hit -> hit.id).containsExactly(1L);
        assertThat(response.partial).isTrue();
        assertThat(response.respondedNodes).isEqualTo(1);
    }

    @Test
    @DisplayName("All nodes responding does not imply complete partition coverage")
    void marksMissingPartitionsAsPartialEvenWhenTopKIsFull() {
        node(parts(0), candidate(1, 0.1));
        node(parts(1), candidate(2, 0.2));
        store(1, 2);

        SearchResponse response = search(2);

        assertThat(response.results).hasSize(2);
        assertThat(response.respondedNodes).isEqualTo(response.totalNodes).isEqualTo(2);
        assertThat(response.activePartitions).isEqualTo(2);
        assertThat(response.totalPartitions).isEqualTo(4);
        assertThat(response.partial).isTrue();
        assertThat(response.failedPartitions).extracting(f -> f.reason).containsOnly(PartitionFailure.Reason.NO_OWNER);
    }

    @Test
    @DisplayName("A null node response counts as unavailable, not as an empty successful shard")
    void treatsNullNodeResultAsUnavailable() {
        node(parts(0, 1), candidate(1, 0.1));
        assignment.put("null-node", parts(2, 3));
        responses.put("null-node", CompletableFuture.completedFuture(null));
        store(1);

        SearchResponse response = search(1);

        assertThat(response.results).extracting(hit -> hit.id).containsExactly(1L);
        assertThat(response.respondedNodes).isEqualTo(1);
        assertThat(response.partial).isTrue();
    }

    @Test
    @DisplayName("An empty fully searched index is complete and needs no hydration")
    void completeEmptyIndexDoesNotHydrate() {
        node(parts(0, 1, 2, 3));

        SearchResponse response = search(10);

        assertThat(response.results).isEmpty();
        assertThat(response.partial).isFalse();
        assertThat(response.activePartitions).isEqualTo(4);
        assertThat(getAllCalls).hasValue(0);
    }

    @Test
    @DisplayName("Without server nodes the result is empty and partition coverage is partial")
    void noServersMeansNoPartitionCoverage() {
        SearchResponse response = search(10);

        assertThat(response.results).isEmpty();
        assertThat(response.totalNodes).isZero();
        assertThat(response.respondedNodes).isZero();
        assertThat(response.activePartitions).isZero();
        assertThat(response.partial).isTrue();
        assertThat(getAllCalls).hasValue(0);
    }

    @Test
    @DisplayName("К4: hydration must not start after gathering exhausts the request deadline")
    void doesNotStartHydrationAfterDeadlineIsExhausted() {
        budgetMs = 100;
        node(parts(0, 1), candidate(1, 0.1));
        // A real future waits out the supplied budget before reporting timeout.
        // The margin crosses the deadline without a timing assertion.
        slowNode(parts(2, 3), Duration.ofMillis(20));
        store(1);

        SearchResponse response = search(1);

        assertThat(getAllCalls).hasValue(0);
        assertThat(response.results).isEmpty();
        assertThat(response.partial).isTrue();
        assertThat(response.timedOut).isTrue();
    }

    // ------------------------------------------------------------ новое: назначение и бюджет

    @Test
    @DisplayName("Ownership change during a query: each partition counted once, unassigned reports ignored")
    void ownershipChangeDuringQueryCountsEachPartitionOnce() {
        // Партиция 1 назначена узлу A, но уже уехала к B: A её не нашёл, B сообщает лишнее.
        NodeSearchResult a = result(new int[]{0}, candidate(1, 0.1));
        a.failedPartitions.add(new PartitionFailure(1, PartitionFailure.Reason.NO_RUNTIME, "a"));
        respond("a", parts(0, 1), a);
        respond("b", parts(2, 3), result(new int[]{1, 2, 3}, candidate(2, 0.2)));
        store(1, 2);

        SearchResponse response = search(2);

        assertThat(response.searchedPartitions).containsExactly(0, 2, 3);
        assertThat(response.failedPartitions).extracting(f -> f.partition, f -> f.reason)
                .containsExactly(tuple(1, PartitionFailure.Reason.NO_RUNTIME));
        assertThat(response.partial).isTrue();
    }

    @Test
    @DisplayName("A searched partition with a pending repair is a known gap and makes the answer partial")
    void knownGapReachesTheClient() {
        NodeSearchResult result = result(new int[]{0, 1, 2, 3}, candidate(1, 0.1));
        result.knownGapPartitions = new int[]{2};
        respond("a", parts(0, 1, 2, 3), result);
        store(1);

        SearchResponse response = search(1);

        assertThat(response.activePartitions).isEqualTo(4);
        assertThat(response.knownGapPartitions).containsExactly(2);
        assertThat(response.partial).isTrue();
    }

    @Test
    void nodesReceiveTheirPartitionsAndTheRemainingBudget() {
        node(parts(0, 1));
        node(parts(2, 3));

        search(5);

        assertThat(requests.values()).extracting(NodeSearchRequest::k).containsOnly(5);
        assertThat(requests.values()).extracting(r -> r.partitions().length).containsOnly(2);
        assertThat(requests.values()).allSatisfy(r ->
                assertThat(r.budgetNanos()).isPositive().isLessThanOrEqualTo(Duration.ofMillis(budgetMs).toNanos()));
    }

    @Test
    void unreportedAssignedPartitionIsNotCoverage() {
        respond("a", parts(0, 1, 2, 3), result(new int[]{0, 1, 2}));

        SearchResponse response = search(1);

        assertThat(response.failedPartitions).extracting(f -> f.partition, f -> f.reason)
                .containsExactly(tuple(3, PartitionFailure.Reason.NOT_REPORTED));
        assertThat(response.partial).isTrue();
    }

    // ------------------------------------------------------------ helpers

    private SearchResponse search(int k) {
        long start = clock.nanoTime();
        Budget budget = Budget.remaining(clock, Duration.ofMillis(budgetMs).toNanos());
        DistributedSearchResult result = coordinator.search(new float[]{1, 0}, k, budget);
        return hydrator.respond(result, k, budget, start);
    }

    private void node(int[] partitionsOfNode, ScoredVector... candidates) {
        respond("node-" + assignment.size(), partitionsOfNode, result(partitionsOfNode, candidates));
    }

    private void respond(String node, int[] partitionsOfNode, NodeSearchResult result) {
        assignment.put(node, partitionsOfNode);
        responses.put(node, CompletableFuture.completedFuture(result));
    }

    private void failedNode(int[] partitionsOfNode, RuntimeException failure) {
        String node = "node-" + assignment.size();
        assignment.put(node, partitionsOfNode);
        responses.put(node, CompletableFuture.failedFuture(failure));
    }

    private void slowNode(int[] partitionsOfNode, Duration extraWait) {
        String node = "node-" + assignment.size();
        assignment.put(node, partitionsOfNode);
        responses.put(node, new CompletableFuture<>());
        slowNodes.put(node, extraWait);
    }

    private static NodeSearchResult result(int[] searched, ScoredVector... candidates) {
        NodeSearchResult result = new NodeSearchResult(new ArrayList<>(List.of(candidates)), searched.length);
        result.searchedPartitions = searched.clone();
        return result;
    }

    private static int[] parts(int... values) {
        return values;
    }

    private void store(long... ids) {
        for (long id : ids) {
            records.put(id, new VectorObject(new float[]{1, 0}, "https://vectors/" + id, "metadata-" + id));
        }
    }

    private static ScoredVector candidate(long id, double distance) {
        return new ScoredVector(id, distance);
    }
}
