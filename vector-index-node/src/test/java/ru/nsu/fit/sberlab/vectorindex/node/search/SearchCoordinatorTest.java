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
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.nsu.fit.sberlab.vectorindex.common.dto.Incompleteness;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexHit;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeSearchResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.PartitionFailure;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchResponse;
import ru.nsu.fit.sberlab.vectorindex.core.Budget;
import ru.nsu.fit.sberlab.vectorindex.testkit.ManualClock;

/**
 * Распределённый поиск без узлов Ignite и сети: назначение, транспорт и часы — подмены,
 * ожидание управляется временем теста (без sleep). Перенос {@code SearchAggregatorServiceImplTest};
 * сценарии гидрации (включая К4) — в {@code IndexSearchClientTest} серверного модуля.
 */
class SearchCoordinatorTest {
    private final ManualClock clock = new ManualClock();
    private final Map<String, int[]> assignment = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<NodeSearchResult>> responses = new HashMap<>();
    private final Map<String, Duration> slowNodes = new HashMap<>();
    private final java.util.Set<String> instantTimeouts = new java.util.HashSet<>();
    private final Map<String, NodeSearchRequest> requests = new HashMap<>();
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

    @BeforeEach
    void setUp() {
        SearchTopology topology = () -> new SearchTopology.Snapshot(partitions, assignment, 1);
        NodeTransport transport = (node, request) -> {
            requests.put(node, request);
            return responses.get(node);
        };
        coordinator = new SearchCoordinator(topology, transport, awaiter);
    }

    @Test
    @DisplayName("Global top-k merges all nodes by ascending distance")
    void mergesGlobalTopK() {
        node(parts(0, 1), candidate(1, 0.8), candidate(3, 0.3));
        node(parts(2, 3), candidate(2, 0.2), candidate(4, 0.1));

        VectorSearchResponse response = search(3);

        assertThat(response.hits).extracting(hit -> hit.key).containsExactly(4L, 2L, 3L);
        assertThat(response.hits).extracting(hit -> hit.distance).containsExactly(0.1, 0.2, 0.3);
        assertThat(response.partial()).isFalse();
        assertThat(response.respondedNodes).isEqualTo(2);
        assertThat(response.totalNodes).isEqualTo(2);
        assertThat(response.searchedPartitions.length).isEqualTo(4);
        assertThat(response.expectedPartitions).isEqualTo(4);
        assertThat(response.searchedPartitions).containsExactly(0, 1, 2, 3);
    }

    @Test
    @DisplayName("Duplicate IDs use their best score, occupy one result slot and are reported")
    void deduplicatesCandidatesWithinAndAcrossNodes() {
        node(parts(0, 1), candidate(7, 0.9), candidate(7, 0.4));
        node(parts(2, 3), candidate(7, 0.1), candidate(8, 0.2));

        VectorSearchResponse response = search(3);

        assertThat(response.hits).extracting(hit -> hit.key).containsExactly(7L, 8L);
        assertThat(response.hits).extracting(hit -> hit.distance).containsExactly(0.1, 0.2);
        assertThat(response.duplicateKeys).as("assignment violation is visible, not silently resolved").isEqualTo(2);
    }

    @Test
    @DisplayName("A failed node does not discard healthy results and makes the answer partial")
    void degradesOnNodeFailure() {
        node(parts(0, 1), candidate(1, 0.1));
        failedNode(parts(2, 3), new IllegalStateException("simulated node failure"));

        VectorSearchResponse response = search(1);

        assertThat(response.hits).extracting(hit -> hit.key).containsExactly(1L);
        assertThat(response.partial()).isTrue();
        assertThat(response.respondedNodes).isEqualTo(1);
        assertThat(response.totalNodes).isEqualTo(2);
        assertThat(response.searchedPartitions.length).isEqualTo(2);
        assertThat(response.failedPartitions).extracting(f -> f.partition, f -> f.reason).containsExactly(
                tuple(2, PartitionFailure.Reason.NODE_FAILED), tuple(3, PartitionFailure.Reason.NODE_FAILED));
    }

    @Test
    @DisplayName("A timed-out node does not prevent collecting subsequent healthy futures")
    void continuesGatheringAfterTimeout() {
        slowNode(parts(0, 1), Duration.ZERO);
        instantTimeouts.add("node-0");
        node(parts(2, 3), candidate(1, 0.1));

        VectorSearchResponse response = search(1);

        assertThat(response.hits).extracting(hit -> hit.key).containsExactly(1L);
        assertThat(response.partial()).isTrue();
        assertThat(response.respondedNodes).isEqualTo(1);
    }

    @Test
    @DisplayName("All nodes responding does not imply complete partition coverage")
    void marksMissingPartitionsAsPartialEvenWhenTopKIsFull() {
        node(parts(0), candidate(1, 0.1));
        node(parts(1), candidate(2, 0.2));

        VectorSearchResponse response = search(2);

        assertThat(response.hits).hasSize(2);
        assertThat(response.respondedNodes).isEqualTo(response.totalNodes).isEqualTo(2);
        assertThat(response.searchedPartitions.length).isEqualTo(2);
        assertThat(response.expectedPartitions).isEqualTo(4);
        assertThat(response.partial()).isTrue();
        assertThat(response.failedPartitions).extracting(f -> f.reason).containsOnly(PartitionFailure.Reason.NO_OWNER);
    }

    @Test
    @DisplayName("A null node response counts as unavailable, not as an empty successful shard")
    void treatsNullNodeResultAsUnavailable() {
        node(parts(0, 1), candidate(1, 0.1));
        assignment.put("null-node", parts(2, 3));
        responses.put("null-node", CompletableFuture.completedFuture(null));

        VectorSearchResponse response = search(1);

        assertThat(response.hits).extracting(hit -> hit.key).containsExactly(1L);
        assertThat(response.respondedNodes).isEqualTo(1);
        assertThat(response.partial()).isTrue();
    }

    @Test
    @DisplayName("An empty fully searched index is a complete answer")
    void completeEmptyIndexIsComplete() {
        node(parts(0, 1, 2, 3));

        VectorSearchResponse response = search(10);

        assertThat(response.hits).isEmpty();
        assertThat(response.partial()).isFalse();
        assertThat(response.searchedPartitions.length).isEqualTo(4);
    }

    @Test
    @DisplayName("Without server nodes the result is empty and partition coverage is partial")
    void noServersMeansNoPartitionCoverage() {
        VectorSearchResponse response = search(10);

        assertThat(response.hits).isEmpty();
        assertThat(response.totalNodes).isZero();
        assertThat(response.respondedNodes).isZero();
        assertThat(response.searchedPartitions.length).isZero();
        assertThat(response.partial()).isTrue();
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

        VectorSearchResponse response = search(2);

        assertThat(response.searchedPartitions).containsExactly(0, 2, 3);
        assertThat(response.failedPartitions).extracting(f -> f.partition, f -> f.reason)
                .containsExactly(tuple(1, PartitionFailure.Reason.NO_RUNTIME));
        assertThat(response.partial()).isTrue();
    }

    @Test
    @DisplayName("A searched partition with a pending repair is a known gap and makes the answer partial")
    void knownGapReachesTheClient() {
        NodeSearchResult result = result(new int[]{0, 1, 2, 3}, candidate(1, 0.1));
        result.knownGapPartitions = new int[]{2};
        respond("a", parts(0, 1, 2, 3), result);

        VectorSearchResponse response = search(1);

        assertThat(response.searchedPartitions.length).isEqualTo(4);
        assertThat(response.knownGapPartitions).containsExactly(2);
        assertThat(response.partial()).isTrue();
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

        VectorSearchResponse response = search(1);

        assertThat(response.failedPartitions).extracting(f -> f.partition, f -> f.reason)
                .containsExactly(tuple(3, PartitionFailure.Reason.NOT_REPORTED));
        assertThat(response.partial()).isTrue();
    }

    // ------------------------------------------------------------ строгий ответ и причины неполноты

    @Test
    void completeAnswerIsCompleteEvenInStrictMode() {
        node(parts(0, 1), candidate(1, 0.1));
        node(parts(2, 3), candidate(2, 0.2));

        VectorSearchResponse response = search(2, false);

        assertThat(response.status).isEqualTo(SearchStatus.COMPLETE);
        assertThat(response.incomplete).isEmpty();
        assertThat(response.hits).extracting(hit -> hit.key).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("Strict mode fails on a known gap even though every partition was searched")
    void strictModeFailsOnKnownGapWithFullCoverage() {
        NodeSearchResult result = result(new int[]{0, 1, 2, 3}, candidate(1, 0.1));
        result.knownGapPartitions = new int[]{2};
        respond("a", parts(0, 1, 2, 3), result);

        VectorSearchResponse strict = search(1, false);

        assertThat(strict.searchedPartitions).hasSize(4);
        assertThat(strict.status).isEqualTo(SearchStatus.FAILED);
        assertThat(strict.incomplete).containsExactly(Incompleteness.KNOWN_GAP);
        assertThat(strict.hits).as("no hits from an incomplete strict answer").isEmpty();
        assertThat(strict.knownGapPartitions).containsExactly(2);
        assertThat(search(1, true).status).isEqualTo(SearchStatus.PARTIAL);
    }

    @Test
    void strictModeFailsOnMissingPartitions() {
        node(parts(0), candidate(1, 0.1));
        node(parts(1), candidate(2, 0.2));          // партиции 2 и 3 без владельца

        VectorSearchResponse strict = search(2, false);

        assertThat(strict.status).isEqualTo(SearchStatus.FAILED);
        assertThat(strict.incomplete).containsExactly(Incompleteness.MISSING_PARTITIONS);
        assertThat(strict.hits).isEmpty();
        assertThat(strict.failedPartitions).extracting(f -> f.reason).containsOnly(PartitionFailure.Reason.NO_OWNER);
    }

    @Test
    void strictModeFailsWhenAnExpectedOwnerTimesOut() {
        node(parts(0, 1), candidate(1, 0.1));
        slowNode(parts(2, 3), Duration.ZERO);

        VectorSearchResponse strict = search(1, false);

        assertThat(strict.status).isEqualTo(SearchStatus.FAILED);
        assertThat(strict.incomplete).contains(Incompleteness.TIMED_OUT, Incompleteness.NODE_NO_RESPONSE,
                Incompleteness.MISSING_PARTITIONS);
        assertThat(strict.hits).isEmpty();
    }

    @Test
    void strictModeFailsWhenAnExpectedOwnerDoesNotRespond() {
        node(parts(0, 1), candidate(1, 0.1));
        assignment.put("null-node", parts(2, 3));
        responses.put("null-node", CompletableFuture.completedFuture(null));

        VectorSearchResponse strict = search(1, false);

        assertThat(strict.status).isEqualTo(SearchStatus.FAILED);
        assertThat(strict.incomplete).contains(Incompleteness.NODE_NO_RESPONSE, Incompleteness.MISSING_PARTITIONS);
        assertThat(strict.failedPartitions).extracting(f -> f.reason)
                .containsOnly(PartitionFailure.Reason.NODE_NO_RESPONSE);
    }

    @Test
    void strictModeFailsOnDuplicateKeysAndOnDefinitionMismatch() {
        node(parts(0, 1), candidate(7, 0.1));
        node(parts(2, 3), candidate(7, 0.2));
        assertThat(search(1, false).incomplete).containsExactly(Incompleteness.DUPLICATE_KEYS);

        assignment.clear();
        responses.clear();
        node(parts(0, 1), candidate(1, 0.1));
        respond("other-definition", parts(2, 3), NodeSearchResult.rejected(parts(2, 3),
                PartitionFailure.Reason.DEFINITION_MISMATCH, "other-definition"));

        VectorSearchResponse strict = search(1, false);
        assertThat(strict.status).isEqualTo(SearchStatus.FAILED);
        assertThat(strict.incomplete).containsExactly(Incompleteness.MISSING_PARTITIONS);
        assertThat(strict.failedPartitions).extracting(f -> f.reason)
                .containsOnly(PartitionFailure.Reason.DEFINITION_MISMATCH);
    }

    @Test
    void nodesReceiveTheIndexAndTheCoordinatorFingerprint() {
        node(parts(0, 1, 2, 3));

        search(1);

        assertThat(requests.values()).extracting(NodeSearchRequest::indexId, NodeSearchRequest::fingerprint)
                .containsOnly(tuple("vectors", "fingerprint"));
    }

    // ------------------------------------------------------------ helpers

    private VectorSearchResponse search(int k) {
        return search(k, true);
    }

    private VectorSearchResponse search(int k, boolean allowPartial) {
        Budget budget = Budget.remaining(clock, Duration.ofMillis(budgetMs).toNanos());
        DistributedSearchResult result = coordinator.search(
                new NodeQuery("vectors", "fingerprint", new float[]{1, 0}, k, false, Map.of()), budget);
        return result.toResponse("vectors", k, allowPartial, 0);
    }

    private void node(int[] partitionsOfNode, IndexHit... candidates) {
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

    private static NodeSearchResult result(int[] searched, IndexHit... candidates) {
        NodeSearchResult result = new NodeSearchResult();
        result.hits = new ArrayList<>(List.of(candidates));
        result.searchedPartitions = searched.clone();
        return result;
    }

    private static int[] parts(int... values) {
        return values;
    }

    private static IndexHit candidate(long id, double distance) {
        return new IndexHit(id, distance);
    }
}
