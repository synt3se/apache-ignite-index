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

import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;

/** Данные демо-кэша {@code VectorObject}: вектор берётся из поля {@code vector}. */
final class IgniteVectorSource implements VectorSource {

    private static final int SCAN_PAGE_SIZE = 1_024;

    private final Ignite ignite;
    private final IgniteCache<Object, VectorObject> cache;
    private final Affinity<Object> affinity;

    IgniteVectorSource(Ignite ignite, String cacheName) {
        this(ignite, ignite.cache(cacheName), ignite.affinity(cacheName));
    }

    IgniteVectorSource(Ignite ignite, IgniteCache<Object, VectorObject> cache, Affinity<Object> affinity) {
        if (cache == null) {
            throw new IllegalStateException("cache is not configured - see ignite-config.xml");
        }
        this.ignite = ignite;
        this.cache = cache;
        this.affinity = affinity;
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
    public float[] read(Object key) {
        VectorObject current = cache.localPeek(key, CachePeekMode.PRIMARY, CachePeekMode.BACKUP);
        if (current == null) {
            current = cache.get(key);
        }
        return current == null ? null : current.getVector();
    }

    @Override
    public void scan(int partition, BiConsumer<Object, float[]> consumer) {
        ScanQuery<Object, VectorObject> scan = new ScanQuery<>();
        scan.setPartition(partition);
        scan.setLocal(true);
        scan.setPageSize(SCAN_PAGE_SIZE);
        try (QueryCursor<Cache.Entry<Object, VectorObject>> cursor = cache.query(scan)) {
            for (Cache.Entry<Object, VectorObject> entry : cursor) {
                VectorObject value = entry.getValue();
                if (value != null && value.getVector() != null) {
                    consumer.accept(entry.getKey(), value.getVector());
                }
            }
        }
    }

    @Override
    public long topologyVersion() {
        return ignite.cluster().topologyVersion();
    }
}
