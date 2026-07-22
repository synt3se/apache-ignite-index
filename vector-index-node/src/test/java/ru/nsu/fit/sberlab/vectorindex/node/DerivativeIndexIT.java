package ru.nsu.fit.sberlab.vectorindex.node;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static java.util.Comparator.comparingDouble;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.List;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.Ignition;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.events.EventType;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import ru.nsu.fit.sberlab.vectorindex.common.ScoredVector;
import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;
import ru.nsu.fit.sberlab.vectorindex.common.indextype.IndexType;
import ru.nsu.fit.sberlab.vectorindex.node.index.PartitionIndexManager;

/**
 * Проверяем «индекс — производная кэша», дёргая менеджер напрямую:
 * записываем ТОЛЬКО в кэш, а поиск идёт по локальным индексам узлов.
 */
class DerivativeIndexIT {

    private static final TcpDiscoveryVmIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder();
    static {
        IP_FINDER.setShared(true);
        IP_FINDER.setAddresses(List.of("127.0.0.1:47500..47509"));
    }

    @AfterEach
    void tearDown() {
        Ignition.stopAll(true);
    }

    @Test
    void putIsIndexedViaEvents() {                       // A: запись в кэш → индекс догоняет
        Ignite a = Ignition.start(cfg("n1"));
        Ignite b = Ignition.start(cfg("n2"));
        awaitServers(a, 2);

        PartitionIndexManager mA = started(a);
        PartitionIndexManager mB = started(b);
        seed(a, 5);

        await().atMost(ofSeconds(15)).pollInterval(ofMillis(300))
                .untilAsserted(() -> assertConverged(3, mA, mB));
    }

    @Test
    void searchStaysCompleteWhenNodeGoesDown() {         // B: узел упал → node-1 достроил
        Ignite a = Ignition.start(cfg("n1"));
        Ignite b = Ignition.start(cfg("n2"));
        awaitServers(a, 2);
        PartitionIndexManager mA = started(a);
        PartitionIndexManager mB = started(b);
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
        PartitionIndexManager mA = started(a);
        PartitionIndexManager mB = started(b);
        seed(a, 5);
        await().atMost(ofSeconds(15)).untilAsserted(() -> assertConverged(0, mA, mB));

        mB.stop();
        Ignition.stop("n2", true);
        await().atMost(ofSeconds(20)).untilAsserted(() -> assertConverged(0, mA));

        Ignite b2 = Ignition.start(cfg("n2"));           // вернулся
        awaitServers(a, 2);
        PartitionIndexManager mB2 = started(b2);

        await().atMost(ofSeconds(30)).pollInterval(ofMillis(500))
                .untilAsserted(() -> assertConverged(2, mA, mB2));
    }

    // ------------------------------------------------------------- helpers

    /** Сырое склеивание локальных выдач БЕЗ дедупа — так видно, эксклюзивно ли владение. */
    private static void assertConverged(int hot, PartitionIndexManager... managers) {
        List<ScoredVector> raw = new ArrayList<>();
        for (PartitionIndexManager m : managers) {
            raw.addAll(m.searchLocal(oneHot(hot), 5));
        }
        assertThat(raw).extracting(ScoredVector::id).doesNotHaveDuplicates(); // один primary на вектор
        assertThat(raw).extracting(ScoredVector::id).hasSize(5);              // ничего не потеряно
        ScoredVector top = raw.stream().min(comparingDouble(ScoredVector::distance)).orElseThrow();
        assertThat(top.id()).isEqualTo((long) hot);                          // верный ближайший
        assertThat(top.distance()).isCloseTo(0.0, within(1e-6));
    }

    private static PartitionIndexManager started(Ignite ignite) {
        PartitionIndexManager m = new PartitionIndexManager(ignite, "vectors", 512, IndexType.JVECTOR_INDEX, null);
        m.start();
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

    private static IgniteConfiguration cfg(String name) {
        IgniteConfiguration c = new IgniteConfiguration();
        c.setIgniteInstanceName(name);
        c.setConsistentId(name);
        c.setPeerClassLoadingEnabled(false);
        c.setIncludeEventTypes(
                EventType.EVT_CACHE_OBJECT_PUT, EventType.EVT_CACHE_OBJECT_REMOVED,
                EventType.EVT_NODE_JOINED, EventType.EVT_NODE_LEFT, EventType.EVT_NODE_FAILED,
                EventType.EVT_CACHE_REBALANCE_STOPPED);
        c.setDiscoverySpi(new TcpDiscoverySpi().setIpFinder(IP_FINDER));

        CacheConfiguration<Long, VectorObject> cache = new CacheConfiguration<>("vectors");
        cache.setCacheMode(CacheMode.PARTITIONED);
        cache.setAtomicityMode(CacheAtomicityMode.ATOMIC);
        cache.setBackups(1);
        cache.setAffinity(new RendezvousAffinityFunction(false, 32));
        c.setCacheConfiguration(cache);
        return c;                       // сервис не деплоим — менеджеры создаём вручную
    }
}