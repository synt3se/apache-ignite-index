package ru.nsu.fit.sberlab.vectorindex.node.index;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteBinary;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.Ignition;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.ClientConfiguration;
import org.apache.ignite.configuration.ClientConnectorConfiguration;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.events.EventType;
import org.apache.ignite.services.ServiceConfiguration;
import org.apache.ignite.spi.communication.tcp.TcpCommunicationSpi;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.nsu.fit.sberlab.vectorindex.common.dto.Incompleteness;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexHit;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexOperationResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.KeyType;
import ru.nsu.fit.sberlab.vectorindex.common.dto.PartitionFailure;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchError;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchRequest;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchResponse;
import ru.nsu.fit.sberlab.vectorindex.core.Metric;
import ru.nsu.fit.sberlab.vectorindex.core.RepairReason;
import ru.nsu.fit.sberlab.vectorindex.node.service.LocalIndexServiceImpl;
import ru.nsu.fit.sberlab.vectorindex.node.service.SearchAggregationService;
import ru.nsu.fit.sberlab.vectorindex.node.service.SearchAggregatorServiceImpl;
import ru.nsu.fit.sberlab.vectorindex.testkit.ExactModel;

/**
 * Универсальная вертикаль на настоящем двухузловом Ignite: определения в статической
 * конфигурации сервисов (их маршаллирует Ignite), поиск и готовность через тонкий клиент.
 *
 * <p>Значения — {@code BinaryObject} типов, классов которых нет ни в одной JVM: поле вектора
 * извлекается без классов приложения, ответ (ключи Long, String, UUID) проходит compute и тонкий
 * клиент без них. Ключ пользовательского типа не индексируется и виден в готовности.
 */
class UniversalIndexIT {

    private static final int CLIENT_PORT = 37800;
    private static final TcpDiscoveryVmIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder();

    static {
        IP_FINDER.setShared(true);
        IP_FINDER.setAddresses(List.of("127.0.0.1:37520..37529"));
    }

    @TempDir
    Path workDirectory;

    private final List<NodeIndexRegistry> manualRegistries = new ArrayList<>();
    private IgniteClient client;

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        manualRegistries.forEach(NodeIndexRegistry::close);
        Ignition.stopAll(true);
    }

    @Test
    void siftLikeL2And128AndClipLikeCosineAnd512GoThroughOneApi() {
        List<VectorIndexConfiguration> indexes = List.of(
                index("sift", "sift", null, 128, Metric.SQUARED_L2, AlgorithmKind.FLAT),
                index("clip", "photos", "embedding", 512, Metric.COSINE, AlgorithmKind.JVECTOR));
        Ignite a = Ignition.start(cfg("u1", services(indexes)));
        Ignition.start(cfg("u2", services(indexes)));
        SearchAggregationService service = service();

        ExactModel<Long> sift = new ExactModel<>(Metric.SQUARED_L2, 128);
        IgniteCache<Long, float[]> siftCache = a.cache("sift");
        Random random = new Random(1);
        for (long id = 0; id < 300; id++) {
            float[] v = random(random, 128);
            siftCache.put(id, v);
            sift.upsert(id, v);
        }
        ExactModel<UUID> clip = new ExactModel<>(Metric.COSINE, 512);
        IgniteCache<Object, BinaryObject> photos = a.cache("photos").withKeepBinary();
        IgniteBinary binary = a.binary();
        for (int i = 0; i < 200; i++) {
            float[] v = random(random, 512);
            UUID key = new UUID(0, i);
            photos.put(key, binary.builder("ru.example.absent.Photo").setField("embedding", v)
                    .setField("url", "https://photos/" + i).build());
            clip.upsert(key, v);
        }
        // Неверный тип поля и ключ пользовательского типа — не индексируются и видны в готовности.
        // Другой тип значения в том же кэше: поле с тем же именем, но не float[].
        photos.put(new UUID(9, 9), binary.builder("ru.example.absent.LegacyPhoto")
                .setField("embedding", "not a vector").build());
        photos.put(binary.builder("ru.example.absent.PhotoKey").setField("id", 1).build(),
                binary.builder("ru.example.absent.Photo").setField("embedding", random(random, 512)).build());

        awaitObservedReady(service, "sift", 300);
        awaitObservedReady(service, "clip", 200);

        for (int q = 0; q < 5; q++) {
            float[] query = random(random, 128);
            VectorSearchResponse found = service.search(exact("sift", query, 10));
            assertThat(found.status).isEqualTo(SearchStatus.COMPLETE);
            assertThat(found.searchedPartitions).hasSize(32);
            assertThat(found.hits).extracting(h -> h.key).containsExactlyElementsOf(sift.topK(query, 10));
            assertThat(found.hits).extracting(IndexHit::keyType).containsOnly(KeyType.LONG);
            assertDistances(found, query, key -> sift.snapshot().get(key), sift);
        }
        for (int q = 0; q < 5; q++) {
            float[] query = random(random, 512);
            VectorSearchResponse found = service.search(exact("clip", query, 10));
            assertThat(found.status).isEqualTo(SearchStatus.COMPLETE);
            assertThat(found.hits).extracting(h -> h.key).containsExactlyElementsOf(clip.topK(query, 10));
            assertThat(found.hits).extracting(IndexHit::keyType).containsOnly(KeyType.UUID);
            assertDistances(found, query, key -> clip.snapshot().get(key), clip);
        }

        IndexStatus clipStatus = service.status("clip");
        assertThat(clipStatus.wrongTypeValuesTotal).isPositive();
        assertThat(clipStatus.unsupportedKeysTotal).isPositive();
        assertThat(service.indexes()).extracting(info -> info.indexId, info -> info.dimension, info -> info.metric)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("sift", 128, "SQUARED_L2"),
                        org.assertj.core.groups.Tuple.tuple("clip", 512, "COSINE"));
        assertThat(service.search(exact("nope", new float[128], 10)).error).isEqualTo(SearchError.UNKNOWN_INDEX);
        assertThat(service.search(exact("sift", new float[127], 10)).error).isEqualTo(SearchError.INVALID_REQUEST);
    }

    @Test
    void twoIndexesOnFieldsOfOneCacheAndUnloadingOneLeavesTheOther() {
        List<VectorIndexConfiguration> indexes = List.of(
                index("title", "docs", "title", 8, Metric.COSINE, AlgorithmKind.FLAT),
                index("body", "docs", "body", 4, Metric.SQUARED_L2, AlgorithmKind.FLAT));
        Ignite a = Ignition.start(cfg("u1", services(indexes)));
        Ignition.start(cfg("u2", services(indexes)));
        SearchAggregationService service = service();
        IgniteCache<Object, BinaryObject> docs = a.cache("docs").withKeepBinary();
        ExactModel<String> body = new ExactModel<>(Metric.SQUARED_L2, 4);
        Random random = new Random(2);
        for (int i = 0; i < 100; i++) {
            float[] b = random(random, 4);
            docs.put("doc-" + i, doc(a, random(random, 8), b));
            body.upsert("doc-" + i, b);
        }
        awaitObservedReady(service, "title", 100);
        awaitObservedReady(service, "body", 100);

        IndexOperationResult unloaded = service.unload("title");

        assertThat(unloaded.confirmed).isTrue();
        assertThat(unloaded.nodes).extracting(n -> n.outcome).containsOnly(IndexOperationResult.Outcome.DONE);
        VectorSearchResponse title = service.search(exact("title", random(random, 8), 5));
        assertThat(title.status).isEqualTo(SearchStatus.PARTIAL);
        assertThat(title.hits).isEmpty();
        assertThat(title.failedPartitions).extracting(f -> f.reason)
                .containsOnly(PartitionFailure.Reason.INDEX_UNLOADED);
        assertThat(service.status("title").unloadedNodes).hasSize(2);
        assertThat(service.indexes()).extracting(info -> info.indexId, info -> info.loaded)
                .contains(org.assertj.core.groups.Tuple.tuple("title", false));

        float[] newBody = {9, 9, 9, 9};                    // другой индекс того же кэша продолжает работать
        docs.put("doc-new", doc(a, random(random, 8), newBody));
        body.upsert("doc-new", newBody);
        await().atMost(ofSeconds(20)).untilAsserted(() -> {
            VectorSearchResponse found = service.search(exact("body", newBody, 3));
            assertThat(found.status).isEqualTo(SearchStatus.COMPLETE);
            assertThat(found.hits).extracting(h -> h.key).containsExactlyElementsOf(body.topK(newBody, 3));
        });
        assertThat(service.unload("title").nodes).extracting(n -> n.outcome)
                .containsOnly(IndexOperationResult.Outcome.ALREADY_UNLOADED);
        assertThat(service.unload("nope")).isNull();
    }

    @Test
    void knownGapUnloadedOwnerAndStrictModeReachTheThinClient() {
        List<VectorIndexConfiguration> indexes = List.of(index("sift", "sift", null, 16, Metric.SQUARED_L2,
                AlgorithmKind.FLAT));
        Ignite a = Ignition.start(cfg("u1", services(indexes)));
        Ignite b = Ignition.start(cfg("u2", services(indexes)));
        SearchAggregationService service = service();
        IgniteCache<Long, float[]> cache = a.cache("sift");
        Random random = new Random(3);
        for (long id = 0; id < 100; id++) {
            cache.put(id, random(random, 16));
        }
        awaitObservedReady(service, "sift", 100);

        // Известный пробел: ожидающая сверка на одной партиции узла a, полное покрытие.
        IgniteIndexAdapter adapterA = NodeIndexRegistry.of(a).adapter("sift");
        adapterA.pauseIndexing(60_000);                     // сверка спит — пробел не закроется сам
        int gap = adapterA.localStatus().readyPartitions[0];
        adapterA.runtime(gap).requestRepair(RepairReason.APPLIER_OVERFLOW);

        VectorSearchResponse partial = service.search(exact("sift", random(random, 16), 5));
        assertThat(partial.searchedPartitions).hasSize(32);
        assertThat(partial.status).isEqualTo(SearchStatus.PARTIAL);
        assertThat(partial.knownGapPartitions).containsExactly(gap);
        assertThat(partial.hits).hasSize(5);
        VectorSearchRequest strictRequest = exact("sift", random(random, 16), 5);
        strictRequest.allowPartial = false;
        VectorSearchResponse strict = service.search(strictRequest);
        assertThat(strict.status).isEqualTo(SearchStatus.FAILED);
        assertThat(strict.incomplete).containsExactly(Incompleteness.KNOWN_GAP);
        assertThat(strict.hits).isEmpty();
        assertThat(service.status("sift").knownGap).isTrue();
        assertThat(service.status("sift").observedReady).isFalse();

        adapterA.resumeIndexing();                          // полная сверка закрывает пробел
        awaitObservedReady(service, "sift", 100);

        // Выгрузка на одном узле: его партиции — INDEX_UNLOADED с этим узлом, ответ не COMPLETE.
        NodeIndexRegistry.of(b).unload("sift");
        VectorSearchResponse degraded = service.search(exact("sift", random(random, 16), 5));
        assertThat(degraded.status).isEqualTo(SearchStatus.PARTIAL);
        assertThat(degraded.failedPartitions).isNotEmpty().allSatisfy(failure -> {
            assertThat(failure.reason).isEqualTo(PartitionFailure.Reason.INDEX_UNLOADED);
            assertThat(failure.node).isEqualTo(b.cluster().localNode().id().toString());
        });
        assertThat(degraded.searchedPartitions.length + degraded.failedPartitions.size()).isEqualTo(32);
        assertThat(degraded.hits).isNotEmpty();
        assertThat(service.status("sift").unloadedNodes).containsExactly(b.cluster().localNode().id().toString());
    }

    @Test
    void sameIndexIdWithAnotherDefinitionOnANodeIsNeverComplete() {
        // Реестры поднимаются вручную: сервис индексов в статической конфигурации обязан быть одинаковым.
        Ignite a = Ignition.start(cfg("u1", List.of(aggregator())));
        Ignite b = Ignition.start(cfg("u2", List.of(aggregator())));
        manualRegistries.add(NodeIndexRegistry.start(a, List.of(
                index("sift", "sift", null, 16, Metric.SQUARED_L2, AlgorithmKind.FLAT).toDefinition())));
        manualRegistries.add(NodeIndexRegistry.start(b, List.of(
                index("sift", "sift", null, 16, Metric.COSINE, AlgorithmKind.FLAT).toDefinition())));
        SearchAggregationService service = service();
        IgniteCache<Long, float[]> cache = a.cache("sift");
        Random random = new Random(4);
        for (long id = 0; id < 50; id++) {
            cache.put(id, random(random, 16));
        }

        await().atMost(ofSeconds(30)).untilAsserted(() -> {
            VectorSearchResponse found = service.search(exact("sift", random(random, 16), 5));
            assertThat(found.searchedPartitions).isNotEmpty();
            assertThat(found.status).isNotEqualTo(SearchStatus.COMPLETE);
            assertThat(found.failedPartitions).extracting(f -> f.reason)
                    .containsOnly(PartitionFailure.Reason.DEFINITION_MISMATCH);
        });
        IndexStatus status = service.status("sift");
        assertThat(status.mismatchedNodes).hasSize(1);
        assertThat(status.searchable).isFalse();
        assertThat(status.observedReady).isFalse();
    }

    // ------------------------------------------------------------ helpers

    private SearchAggregationService service() {
        client = Ignition.startClient(new ClientConfiguration()
                .setAddresses("127.0.0.1:" + CLIENT_PORT + ".." + (CLIENT_PORT + 9)));
        return client.services().serviceProxy(SearchAggregationService.NAME, SearchAggregationService.class, 20_000);
    }

    private static void awaitObservedReady(SearchAggregationService service, String indexId, long expectedVectors) {
        await().atMost(ofSeconds(40)).pollInterval(java.time.Duration.ofMillis(300)).untilAsserted(() -> {
            IndexStatus status = service.status(indexId);
            assertThat(status.liveVectors).isEqualTo(expectedVectors);
            assertThat(status.observedReady).isTrue();
        });
    }

    private static <K> void assertDistances(VectorSearchResponse found, float[] query, Function<K, float[]> vectors,
                                            ExactModel<K> model) {
        for (IndexHit hit : found.hits) {
            @SuppressWarnings("unchecked")
            K key = (K) hit.key;
            assertThat(hit.distance).isCloseTo(model.distance(query, vectors.apply(key)), within(1e-4));
        }
    }

    private static VectorSearchRequest exact(String indexId, float[] query, int k) {
        VectorSearchRequest request = new VectorSearchRequest(indexId, query, k);
        request.exact = true;
        request.timeoutMs = 10_000;
        return request;
    }

    private static BinaryObject doc(Ignite ignite, float[] title, float[] body) {
        return ignite.binary().builder("ru.example.absent.Doc").setField("title", title).setField("body", body).build();
    }

    private static float[] random(Random random, int dimension) {
        float[] v = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            v[i] = random.nextFloat() * 2 - 1;
        }
        return v;
    }

    private static VectorIndexConfiguration index(String name, String cache, String field, int dimension,
                                                  Metric metric, AlgorithmKind algorithm) {
        VectorIndexConfiguration c = new VectorIndexConfiguration();
        c.setName(name);
        c.setCacheName(cache);
        c.setField(field);
        c.setDimension(dimension);
        c.setMetric(metric);
        c.setAlgorithm(algorithm);
        JVectorProperties p = IndexDefinitionTest.properties();
        p.setMinAdditionsBeforeRebuild(10_000); // ITs exercise the vertical, not graph merges
        c.setJvectorProperties(p);
        return c;
    }

    private static List<ServiceConfiguration> services(List<VectorIndexConfiguration> indexes) {
        LocalIndexServiceImpl local = new LocalIndexServiceImpl();
        local.setIndexes(indexes);
        ServiceConfiguration localConfig = new ServiceConfiguration();
        localConfig.setName("LocalIndexService");
        localConfig.setMaxPerNodeCount(1);
        localConfig.setTotalCount(0);
        localConfig.setService(local);
        return List.of(localConfig, aggregator());
    }

    private static ServiceConfiguration aggregator() {
        ServiceConfiguration config = new ServiceConfiguration();
        config.setName(SearchAggregationService.NAME);
        config.setMaxPerNodeCount(1);
        config.setTotalCount(0);
        config.setService(new SearchAggregatorServiceImpl());
        return config;
    }

    private IgniteConfiguration cfg(String name, List<ServiceConfiguration> services) {
        IgniteConfiguration c = new IgniteConfiguration();
        c.setIgniteInstanceName(name);
        c.setConsistentId(name);
        c.setLocalHost("127.0.0.1");
        c.setWorkDirectory(workDirectory.resolve(name).toString());
        c.setDataStorageConfiguration(new DataStorageConfiguration().setDefaultDataRegionConfiguration(
                new DataRegionConfiguration().setInitialSize(32L * 1024 * 1024).setMaxSize(96L * 1024 * 1024)));
        c.setPeerClassLoadingEnabled(false);
        c.setIncludeEventTypes(
                EventType.EVT_CACHE_OBJECT_PUT, EventType.EVT_CACHE_OBJECT_REMOVED,
                EventType.EVT_NODE_JOINED, EventType.EVT_NODE_LEFT, EventType.EVT_NODE_FAILED,
                EventType.EVT_CACHE_REBALANCE_STOPPED);
        c.setDiscoverySpi(new TcpDiscoverySpi().setIpFinder(IP_FINDER).setLocalPort(37520).setLocalPortRange(9));
        c.setCommunicationSpi(new TcpCommunicationSpi().setLocalPort(37220).setLocalPortRange(9));
        c.setClientConnectorConfiguration(new ClientConnectorConfiguration().setPort(CLIENT_PORT).setPortRange(9));
        c.setCacheConfiguration(cache("sift"), cache("photos"), cache("docs"));
        c.setServiceConfiguration(services.toArray(new ServiceConfiguration[0]));
        return c;
    }

    private static CacheConfiguration<Object, Object> cache(String name) {
        CacheConfiguration<Object, Object> cache = new CacheConfiguration<>(name);
        cache.setCacheMode(CacheMode.PARTITIONED);
        cache.setAtomicityMode(CacheAtomicityMode.ATOMIC);
        cache.setBackups(1);
        cache.setAffinity(new RendezvousAffinityFunction(false, 32));
        return cache;
    }
}
