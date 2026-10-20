package ru.nsu.fit.sberlab.vectorindex.vectorserver.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;
import ru.nsu.fit.sberlab.vectorindex.common.dto.Incompleteness;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexHit;
import ru.nsu.fit.sberlab.vectorindex.common.dto.PartitionFailure;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchError;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchResponse;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchRequest;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchResponse;
import ru.nsu.fit.sberlab.vectorindex.node.search.Awaiter;
import ru.nsu.fit.sberlab.vectorindex.testkit.ManualClock;

/**
 * Поиск и гидрация в прикладном слое: бюджет начинается на сервере и ограничивает вызов
 * сервиса, повтор и гидрацию. Сценарии гидрации перенесены из {@code SearchCoordinatorTest}
 * (прежде {@code SearchAggregatorServiceImplTest}) без изменения утверждений.
 */
class IndexSearchClientTest {

    private final ManualClock clock = new ManualClock();
    private final Map<Long, VectorObject> records = new HashMap<>();
    private final AtomicInteger getAllCalls = new AtomicInteger();
    private final List<Long> serviceTimeouts = new ArrayList<>();
    private IndexSearchClient.Service service;
    private long budgetMs = 2_000;

    @BeforeEach
    void setUp() {
        service = (request, timeoutMs) -> complete();
    }

    @Test
    @DisplayName("Hydration preserves score order and attaches the corresponding record")
    void hydrationKeepsSearchOrderAndRecordMetadata() {
        found(complete(candidate(1, 0.1), candidate(2, 0.2), candidate(3, 0.3)));
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
        IndexHit[] candidates = IntStream.range(0, 300)
                .mapToObj(id -> candidate(id, id / 300.0)).toArray(IndexHit[]::new);
        found(complete(candidates));
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
        found(complete(candidate(1, 0.1), candidate(2, 0.2), candidate(3, 0.3)));
        store(2);

        SearchResponse response = search(10);

        assertThat(response.results).extracting(hit -> hit.id).containsExactly(2L);
        assertThat(response.partial).isFalse();
    }

    @Test
    @DisplayName("An empty fully searched index is complete and needs no hydration")
    void completeEmptyIndexDoesNotHydrate() {
        found(complete());

        SearchResponse response = search(10);

        assertThat(response.results).isEmpty();
        assertThat(response.partial).isFalse();
        assertThat(response.activePartitions).isEqualTo(4);
        assertThat(getAllCalls).hasValue(0);
    }

    @Test
    @DisplayName("Without server nodes the result is empty and partition coverage is partial")
    void noServersMeansNoPartitionCoverage() {
        VectorSearchResponse none = complete();
        none.status = SearchStatus.PARTIAL;
        none.incomplete = new ArrayList<>(List.of(Incompleteness.MISSING_PARTITIONS));
        none.searchedPartitions = new int[0];
        none.respondedNodes = 0;
        none.totalNodes = 0;
        found(none);

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
        // Сбор ответов узлов (вызов сервиса) переходит дедлайн сервера: один узел ответил,
        // второй не успел. Запас пересекает дедлайн без проверок по времени.
        service = (request, timeoutMs) -> {
            clock.advanceNanos(Duration.ofMillis(timeoutMs + 20).toNanos());
            VectorSearchResponse late = complete(candidate(1, 0.1));
            late.status = SearchStatus.PARTIAL;
            late.timedOut = true;
            late.incomplete = new ArrayList<>(List.of(Incompleteness.TIMED_OUT, Incompleteness.NODE_NO_RESPONSE));
            return late;
        };
        store(1);

        SearchResponse response = search(1);

        assertThat(getAllCalls).hasValue(0);
        assertThat(response.results).isEmpty();
        assertThat(response.partial).isTrue();
        assertThat(response.timedOut).isTrue();
    }

    // ------------------------------------------------------------ бюджет на сервере

    @Test
    void serviceCallAndItsRetryAreBoundedByTheRemainingServerBudget() {
        AtomicInteger calls = new AtomicInteger();
        service = (request, timeoutMs) -> {
            serviceTimeouts.add(timeoutMs);
            assertThat(request.timeoutMs).as("the node gets the same remainder").isEqualTo(timeoutMs);
            clock.advanceNanos(Duration.ofMillis(300).toNanos());
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("coordinator node is gone");
            }
            return complete(candidate(1, 0.1));
        };
        store(1);

        SearchResponse response = search(1);

        assertThat(serviceTimeouts).containsExactly(2_000L, 1_700L);
        assertThat(response.results).extracting(hit -> hit.id).containsExactly(1L);
    }

    @Test
    void noRetryAndNoHydrationOnceTheBudgetIsSpentByAFailedCall() {
        budgetMs = 100;
        AtomicInteger calls = new AtomicInteger();
        service = (request, timeoutMs) -> {
            calls.incrementAndGet();
            clock.advanceNanos(Duration.ofMillis(timeoutMs).toNanos());
            throw new IllegalStateException("client timeout");
        };
        store(1);

        SearchResponse response = search(1);

        assertThat(calls).hasValue(1);
        assertThat(getAllCalls).hasValue(0);
        assertThat(response.timedOut).isTrue();
        assertThat(response.partial).isTrue();
    }

    @Test
    void strictSearchThatRunsOutOfBudgetFailsWithoutHits() {
        budgetMs = 50;
        service = (request, timeoutMs) -> {
            clock.advanceNanos(Duration.ofMillis(timeoutMs).toNanos());
            throw new IllegalStateException("client timeout");
        };
        VectorSearchRequest request = new VectorSearchRequest("vectors", new float[]{1, 0}, 1);
        request.allowPartial = false;
        request.timeoutMs = budgetMs;

        VectorSearchResponse response = client().search(request);

        assertThat(response.status).isEqualTo(SearchStatus.FAILED);
        assertThat(response.incomplete).containsExactly(Incompleteness.TIMED_OUT);
        assertThat(response.hits).isEmpty();
    }

    @Test
    void coverageAndStatusReachTheDemoResponse() {
        VectorSearchResponse partial = complete(candidate(1, 0.1));
        partial.status = SearchStatus.PARTIAL;
        partial.incomplete = new ArrayList<>(List.of(Incompleteness.KNOWN_GAP, Incompleteness.MISSING_PARTITIONS));
        partial.searchedPartitions = new int[]{0, 1, 2};
        partial.knownGapPartitions = new int[]{1};
        partial.failedPartitions = new ArrayList<>(List.of(
                new PartitionFailure(3, PartitionFailure.Reason.INDEX_UNLOADED, "node-b")));
        partial.duplicateKeys = 0;
        found(partial);
        store(1);

        SearchResponse response = search(1);

        assertThat(response.partial).isTrue();
        assertThat(response.searchedPartitions).containsExactly(0, 1, 2);
        assertThat(response.knownGapPartitions).containsExactly(1);
        assertThat(response.failedPartitions).extracting(f -> f.partition, f -> f.reason, f -> f.node)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(3, PartitionFailure.Reason.INDEX_UNLOADED, "node-b"));
        assertThat(response.totalPartitions).isEqualTo(4);
    }

    @Test
    void requestErrorsAreNotHydrated() {
        found(VectorSearchResponse.failed("nope", SearchError.UNKNOWN_INDEX, "unknown index: nope"));

        assertThatThrownBy(() -> search(1)).isInstanceOfSatisfying(IndexRequestException.class,
                e -> assertThat(e.error()).isEqualTo(SearchError.UNKNOWN_INDEX));
        assertThat(getAllCalls).hasValue(0);
    }

    // ------------------------------------------------------------ helpers

    private SearchResponse search(int k) {
        return client().searchWithDocuments("vectors", new float[]{1, 0}, k);
    }

    private IndexSearchClient client() {
        DocumentStore documents = ids -> {
            getAllCalls.incrementAndGet();
            // Reverse key order deliberately: getAll does not promise distance order.
            Map<Long, VectorObject> loaded = new LinkedHashMap<>();
            ids.stream().sorted(java.util.Comparator.reverseOrder()).forEach(id -> {
                if (records.containsKey(id)) loaded.put(id, records.get(id));
            });
            return CompletableFuture.completedFuture(loaded);
        };
        return new IndexSearchClient(service, new DemoHydrator(documents, Awaiter.BLOCKING, clock), clock,
                Duration.ofMillis(budgetMs).toNanos());
    }

    private void found(VectorSearchResponse response) {
        service = (request, timeoutMs) -> response;
    }

    private static VectorSearchResponse complete(IndexHit... hits) {
        VectorSearchResponse response = new VectorSearchResponse();
        response.indexId = "vectors";
        response.status = SearchStatus.COMPLETE;
        response.hits = new ArrayList<>(List.of(hits));
        response.expectedPartitions = 4;
        response.searchedPartitions = new int[]{0, 1, 2, 3};
        response.respondedNodes = 2;
        response.totalNodes = 2;
        return response;
    }

    private void store(long... ids) {
        for (long id : ids) {
            records.put(id, new VectorObject(new float[]{1, 0}, "https://vectors/" + id, "metadata-" + id));
        }
    }

    private static IndexHit candidate(long id, double distance) {
        return new IndexHit(id, distance);
    }
}
