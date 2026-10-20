package ru.nsu.fit.sberlab.vectorindex.node.index;

import java.util.function.BiConsumer;

import javax.cache.Cache;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.CachePeekMode;
import org.apache.ignite.cache.affinity.Affinity;
import org.apache.ignite.cache.query.QueryCursor;
import org.apache.ignite.cache.query.ScanQuery;
import org.apache.ignite.cluster.ClusterNode;

/**
 * Данные кэша индекса в бинарном виде ({@code withKeepBinary()}): вектор — поле значения
 * ({@link VectorExtractor}), классы приложения на узле не нужны. Ключи передаются как есть;
 * их тип проверяет адаптер.
 */
final class IgniteVectorSource implements VectorSource {

    private static final int SCAN_PAGE_SIZE = 1_024;

    private final Ignite ignite;
    private final IgniteCache<Object, Object> cache;
    private final Affinity<Object> affinity;
    private final String field;

    IgniteVectorSource(Ignite ignite, String cacheName, String field) {
        this(ignite, binary(ignite.cache(cacheName), cacheName), ignite.affinity(cacheName), field);
    }

    IgniteVectorSource(Ignite ignite, IgniteCache<Object, Object> binaryCache, Affinity<Object> affinity, String field) {
        this.ignite = ignite;
        this.cache = binaryCache;
        this.affinity = affinity;
        this.field = field;
    }

    private static IgniteCache<Object, Object> binary(IgniteCache<Object, Object> cache, String cacheName) {
        if (cache == null) {
            throw new IllegalStateException("cache '" + cacheName + "' is not configured on this node");
        }
        return cache.withKeepBinary();
    }

    @Override
    public int[] ownedPrimaryPartitions() {
        return affinity.primaryPartitions(ignite.cluster().localNode());
    }

    @Override
    public boolean isLocalPrimary(int partition) {
        ClusterNode owner = affinity.mapPartitionToNode(partition);
        return owner != null && owner.isLocal();
    }

    @Override
    public int partitionOf(Object key) {
        return affinity.partition(key);
    }

    @Override
    public SourceValue read(Object key) {
        Object current = cache.localPeek(key, CachePeekMode.PRIMARY, CachePeekMode.BACKUP);
        if (current == null) {
            current = cache.get(key);
        }
        return VectorExtractor.extract(current, field);
    }

    @Override
    public void scan(int partition, BiConsumer<Object, SourceValue> consumer) {
        ScanQuery<Object, Object> scan = new ScanQuery<>();
        scan.setPartition(partition);
        scan.setLocal(true);
        scan.setPageSize(SCAN_PAGE_SIZE);
        try (QueryCursor<Cache.Entry<Object, Object>> cursor = cache.query(scan)) {
            for (Cache.Entry<Object, Object> entry : cursor) {
                consumer.accept(entry.getKey(), VectorExtractor.extract(entry.getValue(), field));
            }
        }
    }

    @Override
    public long topologyVersion() {
        return ignite.cluster().topologyVersion();
    }
}
