package ru.nsu.fit.vector.node.index;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.cache.Cache;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.cache.CachePeekMode;
import org.apache.ignite.cache.affinity.Affinity;
import org.apache.ignite.cache.query.QueryCursor;
import org.apache.ignite.cache.query.ScanQuery;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.events.CacheEvent;
import org.apache.ignite.events.Event;
import org.apache.ignite.events.EventType;

import ru.nsu.fit.vector.common.ScoredVector;
import ru.nsu.fit.vector.common.VectorObject;
import ru.nsu.fit.vector.common.dto.NodeStats;
import ru.nsu.fit.vector.common.indextype.IndexType;
/**
 * Индексная плоскость узла: индекс - производная кэша.
 * События кэша - StripedApplier - applyKey.
 * Топология/период - reconcile: drop уехавших, rebuild приехавших/грязных сканом.
 */
public final class PartitionIndexManager {

    private static final int APPLIER_STRIPES = 4;
    private static final int APPLIER_QUEUE = 16_384;
    private static final long RECONCILE_DEBOUNCE_MS = 2_000;
    private static final long RECONCILE_INTERVAL_MS = 30_000;

    private final Ignite ignite;
    private final String cacheName;

    private final ConcurrentHashMap<Integer, PartitionState> partitions = new ConcurrentHashMap<>();
    private final Set<Integer> dirty = ConcurrentHashMap.newKeySet();
    private final Set<Integer> rebuildQueued = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean reconcileDebounce = new AtomicBoolean();
    private final AtomicBoolean reconcileRunning = new AtomicBoolean();
    private volatile boolean stopped;

    private Affinity<Long> affinity;
    private IgniteCache<Long, VectorObject> cache;
    private StripedApplier applier;
    private ExecutorService rebuildPool;
    private ScheduledExecutorService housekeeper;

    private IgniteLogger log;
    private final AtomicLong applied = new AtomicLong();
    private long lastAppliedLogged = 0;

    private final IndexType indexType;
    private final int dimension;

    public PartitionIndexManager(Ignite ignite, String cacheName, int dimension, IndexType indexType) {
        this.ignite = ignite;
        this.cacheName = cacheName;
        this.dimension = dimension;
        this.indexType = indexType;
    }

    public void start() {
        cache = ignite.cache(cacheName);
        if (cache == null) {
            throw new IllegalStateException("Cache '" + cacheName + "' is not configured - see ignite-config.xml");
        }

        log = ignite.log();

        affinity = ignite.affinity(cacheName);
        applier = new StripedApplier(APPLIER_STRIPES, APPLIER_QUEUE);
        rebuildPool = Executors.newFixedThreadPool(2, factory("vindex-rebuild-"));
        housekeeper = Executors.newSingleThreadScheduledExecutor(factory("vindex-housekeeper-"));

        NodeIndexContext.register(this);

        ignite.events().localListen(this::onCacheEvent,
                EventType.EVT_CACHE_OBJECT_PUT, EventType.EVT_CACHE_OBJECT_REMOVED);
        ignite.events().localListen(this::onTopologyEvent,
                EventType.EVT_NODE_JOINED, EventType.EVT_NODE_LEFT,
                EventType.EVT_NODE_FAILED, EventType.EVT_CACHE_REBALANCE_STOPPED);

        housekeeper.schedule(() -> safeReconcile(true), 1, TimeUnit.SECONDS);
        housekeeper.scheduleWithFixedDelay(() -> {
            long total = applied.get();
            long delta = total - lastAppliedLogged;
            lastAppliedLogged = total;
            long backlog = applier.backlog();
            if (delta > 0 || backlog > 0 || !dirty.isEmpty())
                log.info("[vindex] applied=" + delta + "/30s (total=" + total + "), backlog=" + applier.backlog());
            safeReconcile(false);
        }, RECONCILE_INTERVAL_MS, RECONCILE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("[vindex] started on " + ignite.cluster().localNode().consistentId());
    }

    public void stop() {
        stopped = true;
        NodeIndexContext.unregister();
        if (housekeeper != null) housekeeper.shutdownNow();
        if (rebuildPool != null) rebuildPool.shutdownNow();
        if (applier != null) applier.close();
        partitions.clear();
    }

    private boolean onCacheEvent(Event evt) {
        if (stopped) return true;
        if (!(evt instanceof CacheEvent ce) || !cacheName.equals(ce.cacheName())) return true;
        Object rawKey = ce.key();
        if (!(rawKey instanceof Number num)) return true;
        long key = num.longValue();
        int p = affinity.partition(key);
        PartitionState state = partitions.get(p);
        if (state == null) return true;                       // не наша primary-партиция
        if (!applier.submit(p, () -> state.onKeyChanged(key, this::applyKey))) {
            if (dirty.add(p))
                log.warning("[vindex] applier queue full - partition " + p + " marked dirty");
            requestReconcile();
        }
        return true;
    }

    private boolean onTopologyEvent(Event evt) {
        if (!stopped) requestReconcile();
        return true;
    }

    private void requestReconcile() {
        if (stopped || !reconcileDebounce.compareAndSet(false, true)) return;
        try {
            housekeeper.schedule(() -> {
                reconcileDebounce.set(false);
                safeReconcile(false);
            }, RECONCILE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            reconcileDebounce.set(false);
        }
    }

    public void forceReconcile(boolean rebuildAll) {
        safeReconcile(rebuildAll);
    }

    private void safeReconcile(boolean rebuildAll) {
        if (stopped || !reconcileRunning.compareAndSet(false, true)) return;
        try {
            reconcile(rebuildAll);
        } catch (Exception ignored) {
            // повторит следующий тик
        } finally {
            reconcileRunning.set(false);
        }
    }

    private void reconcile(boolean rebuildAll) {
        ClusterNode local = ignite.cluster().localNode();
        int[] owned = affinity.primaryPartitions(local);
        Set<Integer> ownedSet = new HashSet<>();
        for (int p : owned) ownedSet.add(p);

        partitions.keySet().removeIf(p -> !ownedSet.contains(p));   // уехавшие - выбросить
        dirty.removeIf(p -> !ownedSet.contains(p));
        int scheduled = 0;
        for (int p : owned) {
            PartitionState st = partitions.get(p);
            if (rebuildAll || st == null || dirty.remove(p)) { scheduleRebuild(p); scheduled++; }
        }
        if (scheduled > 0)
            log.info("[vindex] reconcile: owned=" + owned.length
                    + ", rebuilds=" + scheduled + (rebuildAll ? " (full)" : ""));
    }

    private void scheduleRebuild(int partition) {
        if (stopped || !rebuildQueued.add(partition)) return;
        try {
            rebuildPool.submit(() -> runRebuild(partition));
        } catch (RejectedExecutionException shuttingDown) {
            rebuildQueued.remove(partition);
        }
    }

    private void runRebuild(int partition) {
        log.info("[vindex] runRebuild START partition=" + partition);
        boolean again = false;
        long t0 = System.nanoTime();
        try {
            ClusterNode owner = affinity.mapPartitionToNode(partition);
            if (owner == null || !owner.isLocal()) {
                partitions.remove(partition);
                return;
            }
            PartitionState st = partitions.computeIfAbsent(partition, PartitionState::new);
            again = st.rebuild(this::buildIndexFor, this::applyKey);
            log.info("[vindex] partition " + partition + " scanned+seeded in "
                    + (System.nanoTime() - t0) / 1_000_000 + " ms"
                    + (again ? " (heavy writes - extra pass)" : ""));
        }catch (IllegalArgumentException e) {
            log.error(
                    "[vindex] partition " + partition
                            + " rebuild FAILED permanently; automatic retry disabled", e);
        }catch (Throwable e) {
            dirty.add(partition);
            requestReconcile();

            log.error("[vindex] partition " + partition + " rebuild FAILED", e);
        } finally {
            rebuildQueued.remove(partition);
            if (again) scheduleRebuild(partition);
        }
    }


    /** Скан партиции по кэшу без сети */
    private PartitionVectorIndex buildIndexFor(int partition) {

        log.info("[vindex] scan START partition=" + partition);

        Map<Long, float[]> vectors = new HashMap<>();

        ScanQuery<Long, VectorObject> scan = new ScanQuery<>();
        scan.setPartition(partition);
        scan.setLocal(true);
        scan.setPageSize(1_024);

        try (QueryCursor<Cache.Entry<Long, VectorObject>> cursor = cache.query(scan)) {
            for (Cache.Entry<Long, VectorObject> entry : cursor) {
                VectorObject object = entry.getValue();

                if (object == null || object.getVector() == null) {
                    continue;
                }

                vectors.put(entry.getKey(), object.getVector());
            }
        }

        log.info("[vindex] scan FINISHED partition=" + partition +
                ", vectors=" + vectors.size());

        PartitionVectorIndex idx;


        int dimension = 512;
        if (indexType == IndexType.JVECTOR_INDEX){
            idx = new JVectorPartitionIndex(dimension);
        }else{
            idx = new BruteForcePartitionIndex();
        }


        log.info("[vindex] seed START partition=" + partition);
        idx.seedAndBuildAsync(vectors);
        log.info("[vindex] seed FINISHED partition=" + partition + " (graph builds in background)");

        return idx;
    }
    /** Читаем текущее значение кэша. */
    private void applyKey(PartitionVectorIndex idx, long key) {
        VectorObject cur = cache.localPeek(key, CachePeekMode.PRIMARY, CachePeekMode.BACKUP);
        if (cur == null) cur = cache.get(key);
        if (cur == null || cur.getVector() == null) idx.delete(key);
        else idx.add(key, cur.getVector());
        applied.incrementAndGet();
    }

    public List<ScoredVector> searchLocal(float[] query, int count) {
        PriorityQueue<ScoredVector> top = new PriorityQueue<>(
                Comparator.comparingDouble(ScoredVector::distance).reversed());
        for (PartitionState st : partitions.values()) {
            PartitionVectorIndex idx = st.indexOrNull();
            if (idx == null) continue;
            for (ScoredVector c : idx.search(query, count)) {
                if (top.size() < count) top.add(c);
                else if (top.peek() != null && c.distance() < top.peek().distance()) {
                    top.poll();
                    top.add(c);
                }
            }
        }
        List<ScoredVector> out = new ArrayList<>(top);
        out.sort(Comparator.comparingDouble(ScoredVector::distance));
        return out;
    }

    public NodeStats localStats() {
        NodeStats s = new NodeStats();
        s.nodeId = String.valueOf(ignite.cluster().localNode().consistentId());
        int active = 0;
        long live = 0;
        for (PartitionState st : partitions.values()) {
            if (st.isActive()) {
                active++;
                PartitionVectorIndex idx = st.indexOrNull();
                if (idx != null) live += idx.size();
            }
        }
        s.ownedPartitions = partitions.size();
        s.activePartitions = active;
        s.liveVectors = live;
        s.applierBacklog = applier == null ? 0 : applier.backlog();
        s.dirtyPartitions = dirty.size();
        s.appliedTotal = applied.get();

        s.engine = indexType.name();
        s.dimension = dimension;
        s.indexMemoryEstimateBytes = live * bytesPerVectorEstimate();
        Runtime rt = Runtime.getRuntime();
        s.heapUsedBytes = rt.totalMemory() - rt.freeMemory();
        s.heapMaxBytes = rt.maxMemory();
        s.uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();

        return s;
    }

    /**
     * Оценка heap-памяти на вектор (ARCHITECTURE-V2 §11.2) — формула, не замер.
     * JVector: ДВЕ копии вектора (float[] + VectorFloat движка) + рёбра графа
     * (M=32) + служебные мапы. Brute-force: одна копия + запись мапы.
     */
    private long bytesPerVectorEstimate() {
        long vectorBytes = 16L + (long) dimension * Float.BYTES;       // float[] с заголовком
        if (indexType == IndexType.JVECTOR_INDEX) {
            return 2 * vectorBytes + 2L * 32 * Integer.BYTES + 150;    // 4.5 КиБ при dim=512
        }
        return vectorBytes + 100;                                      // 2.1 КиБ при dim=512
    }

    public void clearAll() {
        for (PartitionState st : partitions.values()) {
            PartitionVectorIndex idx = st.indexOrNull();
            if (idx != null) idx.clear();
        }
        partitions.clear();
    }

    private static ThreadFactory factory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}