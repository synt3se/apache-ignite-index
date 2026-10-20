package ru.nsu.fit.sberlab.vectorindex.node.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.ignite.logger.NullLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexNodeStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexOperationResult.Outcome;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeSearchResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeStats;
import ru.nsu.fit.sberlab.vectorindex.common.dto.PartitionFailure;
import ru.nsu.fit.sberlab.vectorindex.core.Metric;
import ru.nsu.fit.sberlab.vectorindex.core.PartitionRuntime;
import ru.nsu.fit.sberlab.vectorindex.core.Phase;
import ru.nsu.fit.sberlab.vectorindex.core.maintenance.Backoff;
import ru.nsu.fit.sberlab.vectorindex.core.maintenance.BuildService;
import ru.nsu.fit.sberlab.vectorindex.node.search.NodeQuery;
import ru.nsu.fit.sberlab.vectorindex.node.search.NodeSearchRequest;
import ru.nsu.fit.sberlab.vectorindex.testkit.ManualClock;
import ru.nsu.fit.sberlab.vectorindex.testkit.ManualDelayedExecutor;
import ru.nsu.fit.sberlab.vectorindex.testkit.ManualExecutor;
import ru.nsu.fit.sberlab.vectorindex.testkit.RuntimeHarness;

/**
 * Реестр индексов узла: два индекса на разных полях одного кэша, общие ресурсы, выгрузка одного
 * без влияния на другой, явные причины отказа поиска. Источник каждого индекса — карта в памяти
 * (извлечение поля проверяется в {@code IgniteVectorSourceTest} и {@code UniversalIndexIT}).
 */
@Timeout(15)
class NodeIndexRegistryTest {

    private static final IndexDefinition TITLE =
            IndexDefinition.of("title", "docs", "title", 2, Metric.COSINE, AlgorithmKind.FLAT, null);
    private static final IndexDefinition BODY =
            IndexDefinition.of("body", "docs", "body", 2, Metric.SQUARED_L2, AlgorithmKind.FLAT, null);

    private final ManualExecutor scans = new ManualExecutor();
    private final ManualExecutor builds = new ManualExecutor();
    private final ManualClock clock = new ManualClock();
    private final ManualDelayedExecutor delayed = new ManualDelayedExecutor(clock);
    private final StripedApplier applier = new StripedApplier(1, 64);
    private final AtomicInteger sharedResourcesClosed = new AtomicInteger();
    private final NodeResources resources = new NodeResources(applier, scans, Runnable::run, delayed, null,
            new BuildService(builds, delayed, Backoff.exponential(Duration.ofSeconds(1), Duration.ofSeconds(8)),
                    RuntimeHarness.BUILD_POOL, RuntimeHarness.BUILD_POOL),
            List.of(applier::close, sharedResourcesClosed::incrementAndGet));
    private final Map<String, FakeVectorSource> sources = new HashMap<>();
    private final Map<String, IgniteIndexAdapter> created = new HashMap<>();
    private NodeIndexRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.close();
        }
    }

    @Test
    void unloadingOneIndexLeavesTheOtherIndexOnTheSameCacheUntouched() throws Exception {
        registry = registry(TITLE, BODY);
        source("title").put(2, 1, 0);
        source("body").put(2, 0, 3);
        rebuild("title");
        rebuild("body");
        PartitionRuntime<Object> titleRuntime = created.get("title").runtime(0);
        PartitionRuntime<Object> bodyRuntime = created.get("body").runtime(0);

        assertThat(registry.unload("title")).isEqualTo(Outcome.DONE);

        assertThat(titleRuntime.isClosed()).as("the unloaded index closes its own runtimes").isTrue();
        assertThat(created.get("body").runtime(0)).as("the other index keeps its runtime").isSameAs(bodyRuntime);
        assertThat(bodyRuntime.phase()).isEqualTo(Phase.READY);
        assertThat(created.get("body").searchLocal(new float[]{0, 0}, 10))
                .extracting(h -> h.key, h -> h.distance).containsExactly(tuple(2L, 9.0));

        source("body").put(4, 0, 1);                       // обновления другого индекса идут дальше
        created.get("body").onKeyChanged(4L);
        flushApplier();
        assertThat(created.get("body").searchLocal(new float[]{0, 0}, 10)).extracting(h -> h.key)
                .containsExactly(4L, 2L);
        assertThat(sharedResourcesClosed).as("shared pools stay open").hasValue(0);

        assertThat(registry.presence("title")).isEqualTo(NodeIndexRegistry.Presence.UNLOADED);
        assertThat(registry.presence("body")).isEqualTo(NodeIndexRegistry.Presence.LOADED);
        assertThat(registry.presence("nope")).isEqualTo(NodeIndexRegistry.Presence.UNKNOWN);
        assertThat(registry.unload("title")).isEqualTo(Outcome.ALREADY_UNLOADED);
        assertThat(registry.unload("nope")).isEqualTo(Outcome.UNKNOWN_INDEX);
        assertThat(registry.status("title").state).isEqualTo(IndexNodeStatus.State.UNLOADED);
        assertThat(registry.status("title").fingerprint).isEqualTo(TITLE.fingerprint());
        assertThat(registry.status("nope").state).isEqualTo(IndexNodeStatus.State.UNKNOWN_INDEX);
        assertThat(registry.indexes()).extracting(info -> info.indexId, info -> info.loaded)
                .containsExactly(tuple("title", false), tuple("body", true));
    }

    @Test
    void searchRejectsUnknownUnloadedAndMismatchedIndexesWithExplicitReasons() throws Exception {
        registry = registry(TITLE, BODY);
        source("title").put(2, 1, 0);
        rebuild("title");
        registry.unload("body");

        assertThat(search("title", TITLE.fingerprint()).searchedPartitions).containsExactly(0);
        assertThat(search("title", TITLE.fingerprint()).hits).extracting(h -> h.key).containsExactly(2L);
        assertThat(reasons(search("title", BODY.fingerprint())))
                .as("same indexId, other definition").containsOnly(PartitionFailure.Reason.DEFINITION_MISMATCH);
        assertThat(reasons(search("body", BODY.fingerprint()))).containsOnly(PartitionFailure.Reason.INDEX_UNLOADED);
        assertThat(reasons(search("nope", "x"))).containsOnly(PartitionFailure.Reason.NO_INDEX);
    }

    @Test
    void closeStopsEveryIndexAndClosesTheSharedResourcesOnce() throws Exception {
        registry = registry(TITLE, BODY);
        rebuild("title");
        rebuild("body");
        PartitionRuntime<Object> titleRuntime = created.get("title").runtime(0);
        PartitionRuntime<Object> bodyRuntime = created.get("body").runtime(0);

        registry.close();
        registry.close();

        assertThat(titleRuntime.isClosed()).isTrue();
        assertThat(bodyRuntime.isClosed()).isTrue();
        assertThat(sharedResourcesClosed).hasValue(1);
        registry = null;
    }

    @Test
    void nodeStatsCountSharedResourcesOncePerNodeAndListEveryIndex() throws Exception {
        registry = registry(TITLE, BODY);
        source("title").put(2, 1, 0);
        rebuild("title");
        rebuild("body");
        registry.unload("body");

        NodeStats stats = registry.nodeStats();

        assertThat(stats.indexId).as("no 'vectors' index: the first configured is the default").isEqualTo("title");
        assertThat(stats.liveVectors).isEqualTo(1);
        assertThat(stats.indexes).extracting(s -> s.indexId, s -> s.state).containsExactly(
                tuple("title", IndexNodeStatus.State.LOADED), tuple("body", IndexNodeStatus.State.UNLOADED));
        assertThat(stats.heapMaxBytes).isPositive();
    }

    @Test
    void duplicateIndexNamesAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> registry(TITLE, TITLE));
        assertThatIllegalArgumentException().isThrownBy(() -> registry());
    }

    // ------------------------------------------------------------ helpers

    private NodeIndexRegistry registry(IndexDefinition... definitions) {
        NodeIndexRegistry created = new NodeIndexRegistry(List.of(definitions), resources, definition -> {
            IgniteIndexAdapter adapter = new IgniteIndexAdapter(definition, source(definition.indexId()), resources,
                    clock, new NullLogger());
            this.created.put(definition.indexId(), adapter);
            return adapter;
        }, "node-a", null);
        created.loadAll();
        return created;
    }

    private FakeVectorSource source(String indexId) {
        return sources.computeIfAbsent(indexId, id -> new FakeVectorSource());
    }

    private void rebuild(String indexId) {
        created.get(indexId).forceReconcile(true);
        scans.runAll();
    }

    private NodeSearchResult search(String indexId, String fingerprint) {
        return registry.search(new NodeSearchRequest(
                new NodeQuery(indexId, fingerprint, new float[]{1, 0}, 5, false, Map.of()), new int[]{0}, 1_000_000_000L));
    }

    private static List<PartitionFailure.Reason> reasons(NodeSearchResult result) {
        assertThat(result.searchedPartitions).isEmpty();
        return result.failedPartitions.stream().map(failure -> failure.reason).toList();
    }

    private void flushApplier() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        assertThat(applier.submit(0, done::countDown)).isTrue();
        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
    }
}
