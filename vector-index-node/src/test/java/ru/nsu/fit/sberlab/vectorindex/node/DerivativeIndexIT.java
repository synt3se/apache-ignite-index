package ru.nsu.fit.sberlab.vectorindex.node;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static java.util.Comparator.comparingDouble;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.Ignition;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.events.EventType;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.apache.ignite.spi.communication.tcp.TcpCommunicationSpi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.nsu.fit.sberlab.vectorindex.common.ScoredVector;
import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;
import ru.nsu.fit.sberlab.vectorindex.common.indextype.IndexType;
import ru.nsu.fit.sberlab.vectorindex.common.dto.PartitionFailure;
import ru.nsu.fit.sberlab.vectorindex.core.Budget;
import ru.nsu.fit.sberlab.vectorindex.core.NanoClock;
import ru.nsu.fit.sberlab.vectorindex.node.index.IgniteIndexAdapter;
import ru.nsu.fit.sberlab.vectorindex.node.index.JVectorProperties;
import ru.nsu.fit.sberlab.vectorindex.node.search.Awaiter;
import ru.nsu.fit.sberlab.vectorindex.node.search.DistributedSearchResult;
import ru.nsu.fit.sberlab.vectorindex.node.search.IgniteNodeTransport;
import ru.nsu.fit.sberlab.vectorindex.node.search.IgniteSearchTopology;
import ru.nsu.fit.sberlab.vectorindex.node.search.SearchCoordinator;
/**
 * Проверяем «индекс — производная кэша», дёргая менеджер напрямую:
 * записываем ТОЛЬКО в кэш, а поиск идёт по локальным индексам узлов.
 */
class DerivativeIndexIT {

    @TempDir
    Path workDirectory;

    private final List<IgniteIndexAdapter> managers = new ArrayList<>();

    private static final TcpDiscoveryVmIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder();
    static {
        IP_FINDER.setShared(true);
        IP_FINDER.setAddresses(List.of("127.0.0.1:37500..37509"));
    }

    private static JVectorProperties testJvectorProps() {
        JVectorProperties p = new JVectorProperties();
        p.setM(16);
        p.setEfConstruction(100);
        p.setEfSearch(3);
        p.setNeighborOverflow(1.2f);
        p.setAlpha(1.2f);
        p.setAddHierarchy(true);
        p.setRefineFinalGraph(true);
        p.setAddRebuildRatio(0.10);
        // These ITs exercise cache events and ownership changes. Graph merge races are
        // exercised with controlled barriers in JVectorPartitionIndexTest instead.
        p.setDeleteRebuildRatio(10);
        p.setMinAdditionsBeforeRebuild(10_000);
        return p;
    }

    @AfterEach
    void tearDown() {
        managers.forEach(IgniteIndexAdapter::stop);
        Ignition.stopAll(true);
    }

    @Test
    void putIsIndexedViaEvents() {                       // A: запись в кэш → индекс догоняет
        Ignite a = Ignition.start(cfg("n1"));
        Ignite b = Ignition.start(cfg("n2"));
        awaitServers(a, 2);

        IgniteIndexAdapter mA = started(a);
        IgniteIndexAdapter mB = started(b);
        seed(a, 5);

        await().atMost(ofSeconds(15)).pollInterval(ofMillis(300))
                .untilAsserted(() -> assertConverged(3, mA, mB));
    }

    @Test
    void updatesAndDeletesConvergeThroughCacheEvents() {
        Ignite a = Ignition.start(cfg("n1"));
        Ignite b = Ignition.start(cfg("n2"));
        awaitServers(a, 2);
        IgniteIndexAdapter mA = started(a);
        IgniteIndexAdapter mB = started(b);
        seed(a, 5);
        await().atMost(ofSeconds(15)).untilAsserted(() -> assertConverged(0, mA, mB));

        IgniteCache<Long, VectorObject> cache = a.cache("vectors");
        cache.put(0L, new VectorObject(oneHot(7), "updated-image", "updated-metadata"));
        await().atMost(ofSeconds(15)).untilAsserted(() -> {
            List<ScoredVector> results = new ArrayList<>(mA.searchLocal(oneHot(7), 10));
            results.addAll(mB.searchLocal(oneHot(7), 10));
            assertThat(results).extracting(ScoredVector::id).doesNotHaveDuplicates().hasSize(5);
            assertThat(results).filteredOn(neighbor -> neighbor.id() == 0)
                    .singleElement().satisfies(neighbor ->
                            assertThat(neighbor.distance()).isCloseTo(0, within(1e-6)));
        });

        cache.remove(0L);
        cache.remove(2L);
        await().atMost(ofSeconds(15)).untilAsserted(() -> {
            List<ScoredVector> results = new ArrayList<>(mA.searchLocal(oneHot(7), 10));
            results.addAll(mB.searchLocal(oneHot(7), 10));
            assertThat(results).extracting(ScoredVector::id)
                    .containsExactlyInAnyOrder(1L, 3L, 4L);
        });
    }

    @Test
    void searchStaysCompleteWhenNodeGoesDown() {         // B: узел упал → node-1 достроил
        Ignite a = Ignition.start(cfg("n1"));
        Ignite b = Ignition.start(cfg("n2"));
        awaitServers(a, 2);
        IgniteIndexAdapter mA = started(a);
        IgniteIndexAdapter mB = started(b);
        seed(a, 5);
        await().atMost(ofSeconds(15)).untilAsserted(() -> assertConverged(0, mA, mB));

        mB.stop();
        Ignition.stop("n2", true);

        await().atMost(ofSeconds(20)).pollInterval(ofMillis(500))
                .untilAsserted(() -> assertConverged(3, mA));   // теперь только node-1
    }

    @Test
    void noDuplicatesOrLossAfterNodeRejoin() {           // C: возврат узла → ребилд, эксклюзивное владение
        Ignite a = Ignition.start(cfg("n1"));
        Ignite b = Ignition.start(cfg("n2"));
        awaitServers(a, 2);
        IgniteIndexAdapter mA = started(a);
        IgniteIndexAdapter mB = started(b);
        seed(a, 5);
        await().atMost(ofSeconds(15)).untilAsserted(() -> assertConverged(0, mA, mB));

        mB.stop();
        Ignition.stop("n2", true);
        await().atMost(ofSeconds(20)).untilAsserted(() -> assertConverged(0, mA));

        Ignite b2 = Ignition.start(cfg("n2"));           // вернулся
        awaitServers(a, 2);
        IgniteIndexAdapter mB2 = started(b2);

        await().atMost(ofSeconds(30)).pollInterval(ofMillis(500))
                .untilAsserted(() -> assertConverged(2, mA, mB2));
    }

    @Test
    void distributedSearchThroughComputeJobsReportsCoverage() {   // D: координатор → узлы по назначению
        Ignite a = Ignition.start(cfg("n1"));
        Ignite b = Ignition.start(cfg("n2"));
        awaitServers(a, 2);
        IgniteIndexAdapter mA = started(a);
        IgniteIndexAdapter mB = started(b);
        seed(a, 5);
        await().atMost(ofSeconds(15)).untilAsserted(() -> assertConverged(0, mA, mB));
        SearchCoordinator coordinator = new SearchCoordinator(new IgniteSearchTopology(a, "vectors"),
                new IgniteNodeTransport(a), Awaiter.BLOCKING);

        await().atMost(ofSeconds(15)).untilAsserted(() -> {
            DistributedSearchResult result = coordinator.search(oneHot(3), 5, budget());
            assertThat(result.partial()).isFalse();
            assertThat(result.searchedPartitions()).hasSize(32);
            assertThat(result.hits()).extracting(ScoredVector::id).hasSize(5).doesNotHaveDuplicates();
            assertThat(result.hits().get(0).id()).isEqualTo(3L);
            assertThat(result.duplicateKeys()).isZero();
        });

        mB.stop();                                      // индекс на n2 остановлен, узел жив
        DistributedSearchResult degraded = coordinator.search(oneHot(3), 5, budget());
        assertThat(degraded.partial()).isTrue();
        assertThat(degraded.failedPartitions()).isNotEmpty()
                .extracting(failure -> failure.reason).containsOnly(PartitionFailure.Reason.NO_INDEX);
        assertThat(degraded.searchedPartitions().length + degraded.failedPartitions().size()).isEqualTo(32);
    }

    // ------------------------------------------------------------- helpers

    private static Budget budget() {
        return Budget.remaining(NanoClock.SYSTEM, TimeUnit.SECONDS.toNanos(5));
    }

    /** Сырое склеивание локальных выдач БЕЗ дедупа — так видно, эксклюзивно ли владение. */
    private static void assertConverged(int hot, IgniteIndexAdapter... managers) {
        List<ScoredVector> raw = new ArrayList<>();
        for (IgniteIndexAdapter m : managers) {
            raw.addAll(m.searchLocal(oneHot(hot), 5));
        }
        assertThat(raw).extracting(ScoredVector::id).doesNotHaveDuplicates(); // один primary на вектор
        assertThat(raw).extracting(ScoredVector::id).hasSize(5);              // ничего не потеряно
        ScoredVector top = raw.stream().min(comparingDouble(ScoredVector::distance)).orElseThrow();
        assertThat(top.id()).isEqualTo((long) hot);                          // верный ближайший
        assertThat(top.distance()).isCloseTo(0.0, within(1e-6));
    }

    private IgniteIndexAdapter started(Ignite ignite) {
        IgniteIndexAdapter m = new IgniteIndexAdapter(
                ignite, "vectors", 512, IndexType.JVECTOR_INDEX, testJvectorProps());
        m.start();
        managers.add(m);
        return m;
    }

    private static void seed(Ignite ignite, int n) {
        IgniteCache<Long, VectorObject> cache = ignite.cache("vectors");
        for (int i = 0; i < n; i++) {
            cache.put((long) i, new VectorObject(oneHot(i), "img-" + i, "m"));
        }
    }

    private static float[] oneHot(int i) {
        float[] v = new float[512];
        v[i] = 1f;
        return v;
    }

    private static void awaitServers(Ignite ignite, int n) {
        await().atMost(ofSeconds(20))
                .until(() -> ignite.cluster().forServers().nodes().size() == n);
    }

    private IgniteConfiguration cfg(String name) {
        IgniteConfiguration c = new IgniteConfiguration();
        c.setIgniteInstanceName(name);
        c.setConsistentId(name);
        c.setLocalHost("127.0.0.1");
        c.setWorkDirectory(workDirectory.resolve(name).toString());
        c.setDataStorageConfiguration(new DataStorageConfiguration().setDefaultDataRegionConfiguration(
                new DataRegionConfiguration().setInitialSize(32L * 1024 * 1024).setMaxSize(64L * 1024 * 1024)));
        c.setPeerClassLoadingEnabled(false);
        c.setIncludeEventTypes(
                EventType.EVT_CACHE_OBJECT_PUT, EventType.EVT_CACHE_OBJECT_REMOVED,
                EventType.EVT_NODE_JOINED, EventType.EVT_NODE_LEFT, EventType.EVT_NODE_FAILED,
                EventType.EVT_CACHE_REBALANCE_STOPPED);
        c.setDiscoverySpi(new TcpDiscoverySpi().setIpFinder(IP_FINDER)
                .setLocalPort(37500).setLocalPortRange(9));
        c.setCommunicationSpi(new TcpCommunicationSpi().setLocalPort(37200).setLocalPortRange(9));

        CacheConfiguration<Long, VectorObject> cache = new CacheConfiguration<>("vectors");
        cache.setCacheMode(CacheMode.PARTITIONED);
        cache.setAtomicityMode(CacheAtomicityMode.ATOMIC);
        cache.setBackups(1);
        cache.setAffinity(new RendezvousAffinityFunction(false, 32));
        c.setCacheConfiguration(cache);
        return c;                       // сервис не деплоим — менеджеры создаём вручную
    }
}
