package ru.nsu.fit.sberlab.vectorindex.node.index;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.events.CacheEvent;
import org.apache.ignite.events.Event;
import org.apache.ignite.events.EventType;
import org.apache.ignite.lang.IgnitePredicate;

import ru.nsu.fit.sberlab.vectorindex.common.ScoredVector;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeSearchResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeStats;
import ru.nsu.fit.sberlab.vectorindex.common.indextype.IndexType;
import ru.nsu.fit.sberlab.vectorindex.core.ApplyResult;
import ru.nsu.fit.sberlab.vectorindex.core.Budget;
import ru.nsu.fit.sberlab.vectorindex.core.NanoClock;
import ru.nsu.fit.sberlab.vectorindex.core.OptimizationState;
import ru.nsu.fit.sberlab.vectorindex.core.PartitionRuntime;
import ru.nsu.fit.sberlab.vectorindex.core.Phase;
import ru.nsu.fit.sberlab.vectorindex.core.PublishResult;
import ru.nsu.fit.sberlab.vectorindex.core.RepairReason;
import ru.nsu.fit.sberlab.vectorindex.core.RuntimeConfig;
import ru.nsu.fit.sberlab.vectorindex.core.RuntimeStatus;
import ru.nsu.fit.sberlab.vectorindex.core.ScanSession;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.AlgorithmSearchParams;

/**
 * Адаптер Ignite на текущих событиях: индекс — производная кэша, видимость eventual.
 *
 * <ul>
 *   <li>Событие кэша → полоса применения → перечитывание текущего значения → {@code upsert/delete}
 *       на runtime, захваченный в момент события. Переполнение очереди или ошибка чтения —
 *       запрос сверки этого runtime.</li>
 *   <li>Сверка (топология, период, запросы): уехавшие партиции закрываются, новые получают
 *       runtime до скана, партиции с ожидающей сверкой пересканируются. Скан и версии
 *       записей — протокол ядра; адаптер только читает источник.</li>
 *   <li>Поиск — {@link LocalSearch} по назначенным партициям в пуле поиска узла.</li>
 * </ul>
 */
public final class IgniteIndexAdapter {

    private static final long RECONCILE_DEBOUNCE_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long RECONCILE_INTERVAL_MS = 30_000;

    private final IndexDefinition definition;
    private final VectorSource source;
    private final NodeResources resources;
    private final NanoClock clock;
    private final IgniteLogger log;
    private final Ignite ignite;           // null без кластера (тесты)
    private final String nodeId;
    private final boolean ownsResources;

    private final ConcurrentHashMap<Integer, PartitionRuntime<Object>> runtimes = new ConcurrentHashMap<>();
    private final Set<Integer> scanQueued = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean reconcileDebounce = new AtomicBoolean();
    private final AtomicBoolean reconcileRunning = new AtomicBoolean();
    private volatile boolean stopped;
    private volatile long bulkPauseUntilMs;

    private final LongAdder applied = new LongAdder();
    private final LongAdder applyLatencyNanos = new LongAdder();
    private final AtomicLong applyLatencyMax = new AtomicLong();
    private final LongAdder applierOverflows = new LongAdder();
    private final LongAdder applyErrors = new LongAdder();
    private final LongAdder unsupportedKeys = new LongAdder();
    private final LongAdder reconcileErrors = new LongAdder();

    private IgnitePredicate<Event> cacheListener;
    private IgnitePredicate<Event> topologyListener;

    /** Прежняя конфигурация сервиса: один индекс на узел, COSINE, JVector или перебор. */
    public IgniteIndexAdapter(Ignite ignite, String cacheName, int dimension, IndexType indexType,
                              JVectorProperties properties) {
        this(IndexDefinition.fromLegacy(cacheName, dimension, indexType, properties),
                new IgniteVectorSource(ignite, cacheName),
                NodeResources.create("vindex-" + cacheName), NanoClock.SYSTEM, ignite.log(), ignite,
                String.valueOf(ignite.cluster().localNode().consistentId()), true);
    }

    /** Без кластера: источник, пулы и часы внедряются (тесты). */
    IgniteIndexAdapter(IndexDefinition definition, VectorSource source, NodeResources resources,
                       NanoClock clock, IgniteLogger log) {
        this(definition, source, resources, clock, log, null, "local", false);
    }

    private IgniteIndexAdapter(IndexDefinition definition, VectorSource source, NodeResources resources,
                               NanoClock clock, IgniteLogger log, Ignite ignite, String nodeId, boolean ownsResources) {
        this.definition = definition;
        this.source = source;
        this.resources = resources;
        this.clock = clock;
        this.log = log;
        this.ignite = ignite;
        this.nodeId = nodeId;
        this.ownsResources = ownsResources;
    }

    // ---------------------------------------------------------------- жизненный цикл

    public void start() {
        if (ignite != null) {
            cacheListener = this::onCacheEvent;
            topologyListener = event -> {
                onTopologyChanged();
                return true;
            };
            ignite.events().localListen(cacheListener, EventType.EVT_CACHE_OBJECT_PUT, EventType.EVT_CACHE_OBJECT_REMOVED);
            ignite.events().localListen(topologyListener, EventType.EVT_NODE_JOINED, EventType.EVT_NODE_LEFT,
                    EventType.EVT_NODE_FAILED, EventType.EVT_CACHE_REBALANCE_STOPPED);
            NodeIndexContext.register(ignite, this);
        }
        if (resources.housekeeper != null) {
            resources.housekeeper.schedule(() -> safeReconcile(true), 1, TimeUnit.SECONDS);
            resources.housekeeper.scheduleWithFixedDelay(this::housekeeping,
                    RECONCILE_INTERVAL_MS, RECONCILE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
        log.info("[vindex] adapter started: index=" + definition.indexId() + ", algorithm="
                + definition.algorithm().name() + ", visibility=eventual (cache events)");
    }

    public void stop() {
        stopped = true;
        if (ignite != null) {
            try {
                NodeIndexContext.unregister(ignite, this);
                if (cacheListener != null) {
                    ignite.events().stopLocalListen(cacheListener);
                }
                if (topologyListener != null) {
                    ignite.events().stopLocalListen(topologyListener);
                }
            } catch (IllegalStateException nodeAlreadyStopped) {
                // узел остановлен раньше адаптера: слушатели и nodeLocalMap ушли вместе с ним
            }
        }
        for (PartitionRuntime<Object> runtime : runtimes.values()) {
            runtime.close();
        }
        runtimes.clear();
        if (ownsResources) {
            resources.close();
        }
    }

    // ---------------------------------------------------------------- изменения

    private boolean onCacheEvent(Event event) {
        if (event instanceof CacheEvent cacheEvent && definition.cacheName().equals(cacheEvent.cacheName())) {
            onKeyChanged(cacheEvent.key());
        }
        return true;
    }

    /** Уведомление «ключ изменился»: значение перечитывается при применении. */
    void onKeyChanged(Object rawKey) {
        if (stopped || paused()) {
            return;
        }
        if (!(rawKey instanceof Long key)) {
            unsupportedKeys.increment(); // в v1 транспорт результатов поддерживает только Long
            return;
        }
        int partition = source.partitionOf(key);
        PartitionRuntime<Object> runtime = runtimes.get(partition);
        if (runtime == null) {
            return; // не наша primary-партиция или runtime ещё не создан: его скан прочитает ключ
        }
        long received = clock.nanoTime();
        if (!resources.applier.submit(partition, () -> applyKey(runtime, key, received))) {
            applierOverflows.increment();
            runtime.requestRepair(RepairReason.APPLIER_OVERFLOW);
            log.warning("[vindex] applier queue full - partition " + partition + " needs repair");
            requestReconcile();
        }
    }

    private void applyKey(PartitionRuntime<Object> runtime, Long key, long receivedNanos) {
        try {
            float[] vector = source.read(key);
            ApplyResult result = vector == null ? runtime.delete(key) : runtime.upsert(key, vector);
            if (result.status() == ApplyResult.Status.REJECTED) {
                log.warning("[vindex] rejected vector for key " + key + ": " + result.reason());
            }
            long latency = Math.max(0, clock.nanoTime() - receivedNanos);
            applyLatencyNanos.add(latency);
            applyLatencyMax.accumulateAndGet(latency, Math::max);
        } catch (RuntimeException e) {
            applyErrors.increment();
            runtime.requestRepair(RepairReason.APPLY_FAILED);
            log.error("[vindex] failed to apply key " + key + " - partition needs repair", e);
            requestReconcile();
        } finally {
            applied.increment();
        }
    }

    void onTopologyChanged() {
        if (!stopped) {
            requestReconcile();
        }
    }

    // ---------------------------------------------------------------- сверка

    private void requestReconcile() {
        if (stopped || !reconcileDebounce.compareAndSet(false, true)) {
            return;
        }
        resources.delayed.schedule(() -> {
            reconcileDebounce.set(false);
            safeReconcile(false);
        }, RECONCILE_DEBOUNCE_NANOS);
    }

    public void forceReconcile(boolean rebuildAll) {
        safeReconcile(rebuildAll);
    }

    private void safeReconcile(boolean rebuildAll) {
        if (stopped || paused() || !reconcileRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            reconcile(rebuildAll);
        } catch (RuntimeException e) {
            reconcileErrors.increment();
            log.error("[vindex] reconcile failed; will retry on the next tick", e);
        } finally {
            reconcileRunning.set(false);
        }
    }

    private void reconcile(boolean rebuildAll) {
        Set<Integer> owned = new HashSet<>();
        for (int partition : source.ownedPrimaryPartitions()) {
            owned.add(partition);
        }
        for (Map.Entry<Integer, PartitionRuntime<Object>> entry : List.copyOf(runtimes.entrySet())) {
            if (!owned.contains(entry.getKey()) && runtimes.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().close(); // эпизод владения завершён: поздняя работа отклоняется
            }
        }
        int scheduled = 0;
        for (int partition : owned) {
            PartitionRuntime<Object> runtime = runtimes.get(partition);
            if (runtime == null) {
                runtime = createRuntime(partition);
                PartitionRuntime<Object> raced = runtimes.putIfAbsent(partition, runtime);
                if (raced != null) {
                    runtime.close();
                    runtime = raced;
                }
                scheduled += scheduleScan(partition, runtime) ? 1 : 0;
            } else if (rebuildAll) {
                runtime.requestRepair(RepairReason.MANUAL);
                scheduled += scheduleScan(partition, runtime) ? 1 : 0;
            } else if (runtime.repairPending()) {
                scheduled += scheduleScan(partition, runtime) ? 1 : 0;
            }
        }
        if (scheduled > 0) {
            log.info("[vindex] reconcile: owned=" + owned.size() + ", scans=" + scheduled + (rebuildAll ? " (full)" : ""));
        }
    }

    private PartitionRuntime<Object> createRuntime(int partition) {
        return PartitionRuntime.create(new RuntimeConfig(definition.indexId(), partition, definition.spec(),
                definition.algorithm(), definition.policy(), resources.builds::requestOptimization, clock));
    }

    /** Не больше одного скана на партицию; повтор после скана решают счётчики сверки runtime. */
    private boolean scheduleScan(int partition, PartitionRuntime<Object> runtime) {
        if (stopped || !scanQueued.add(partition)) {
            return false;
        }
        try {
            resources.scanPool.execute(() -> runScan(partition, runtime));
            return true;
        } catch (RejectedExecutionException shuttingDown) {
            scanQueued.remove(partition);
            return false;
        }
    }

    private void runScan(int partition, PartitionRuntime<Object> runtime) {
        boolean again = false;
        long started = clock.nanoTime();
        try {
            if (runtime.isClosed() || runtimes.get(partition) != runtime) {
                return;
            }
            if (!source.isLocalPrimary(partition)) {
                if (runtimes.remove(partition, runtime)) {
                    runtime.close();
                }
                return;
            }
            ScanSession<Object> scan = runtime.beginScan();
            try {
                source.scan(partition, (key, vector) -> {
                    if (key instanceof Long) {
                        scan.offer(key, vector);
                    } else {
                        unsupportedKeys.increment();
                    }
                });
            } catch (RuntimeException e) {
                scan.close();
                runtime.requestRepair(RepairReason.SCAN_FAILED);
                log.error("[vindex] scan of partition " + partition + " failed; previous view stays", e);
                requestReconcile();
                return;
            }
            PublishResult published = runtime.publishScan(scan);
            // Переполнение или пропуск во время этого скана: нужен ещё один проход (К2).
            again = published == PublishResult.PUBLISHED && runtime.repairPending();
            log.info("[vindex] partition " + partition + " scanned in "
                    + TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - started) + " ms: " + published
                    + (again ? " (repair requested during scan - another pass)" : ""));
        } finally {
            scanQueued.remove(partition);
            if (again) {
                scheduleScan(partition, runtime);
            }
        }
    }

    private void housekeeping() {
        long pauseUntil = bulkPauseUntilMs;
        if (pauseUntil != 0 && System.currentTimeMillis() >= pauseUntil) {
            log.warning("[vindex] bulk pause EXPIRED - load never finished? resuming with full rescan");
            resumeIndexing();
        }
        safeReconcile(false);
    }

    // ---------------------------------------------------------------- массовая загрузка (К5, совместимость)

    /** Bulk-режим: события игнорируются, сверка спит. Штатный выход — {@link #resumeIndexing()}. */
    public void pauseIndexing(long ttlMs) {
        bulkPauseUntilMs = System.currentTimeMillis() + ttlMs;
        log.info("[vindex] indexing PAUSED (bulk load, ttl=" + ttlMs + " ms)");
    }

    public void resumeIndexing() {
        bulkPauseUntilMs = 0;
        log.info("[vindex] indexing RESUMED - full rescan");
        forceReconcile(true);
    }

    /** Завершает все эпизоды; следующая сверка создаст runtime заново и просканирует партиции. */
    public void clearAll() {
        for (Map.Entry<Integer, PartitionRuntime<Object>> entry : List.copyOf(runtimes.entrySet())) {
            if (runtimes.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().close();
            }
        }
    }

    private boolean paused() {
        return System.currentTimeMillis() < bulkPauseUntilMs;
    }

    // ---------------------------------------------------------------- поиск и статистика

    /**
     * Поиск по назначенным партициям ({@code null} — по всем локальным runtime).
     *
     * @throws IllegalArgumentException неверный запрос
     */
    public NodeSearchResult search(int[] partitions, float[] query, int k, AlgorithmSearchParams params, Budget budget) {
        definition.spec().prepareQuery(query); // неверный запрос — ошибка вызывающего, до рассылки по пулу
        int[] assigned = partitions != null ? partitions
                : runtimes.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        return LocalSearch.search(runtimes, assigned, query, k, params, budget, resources.searchPool,
                nodeId, source.topologyVersion());
    }

    /** Совместимость: top-k по всем локальным партициям в фазе READY. */
    public List<ScoredVector> searchLocal(float[] query, int count) {
        return search(null, query, count, null, Budget.unlimited()).results;
    }

    public int activePartitionsCount() {
        int ready = 0;
        for (PartitionRuntime<Object> runtime : runtimes.values()) {
            if (runtime.status().phase() == Phase.READY) {
                ready++;
            }
        }
        return ready;
    }

    public NodeStats localStats() {
        NodeStats s = new NodeStats();
        s.nodeId = nodeId;
        s.ownedPartitions = runtimes.size();
        long nowMs = 0;
        for (PartitionRuntime<Object> runtime : runtimes.values()) {
            RuntimeStatus st = runtime.status();
            if (st.phase() == Phase.READY) {
                s.activePartitions++;
                s.liveVectors += st.liveCount();
            } else if (st.phase() == Phase.LOADING) {
                s.loadingPartitions++;
            }
            if (st.repairPending()) {
                s.repairPendingPartitions++;
                nowMs = Math.max(nowMs, TimeUnit.NANOSECONDS.toMillis(st.repairPendingAgeNanos()));
            }
            if (st.optimization() == OptimizationState.FAILED) {
                s.optimizationFailedPartitions++;
            }
            s.enginePendingVectors += st.deltaLive();
            s.deltaEntries += st.deltaEntries();
            s.frozenLayers += st.frozenLayers();
            s.dedupSkippedTotal += st.dedupSkipped();
            s.buildFailuresTotal += st.buildFailures();
            s.publishRejectedTotal += st.publishRejected();
            s.rejectedVectorsTotal += st.rejectedTotal();
            s.writerLockWaitNanosMax = Math.max(s.writerLockWaitNanosMax, st.writerLockWaitNanosMax());
            s.lastBuildDurationNanosMax = Math.max(s.lastBuildDurationNanosMax, st.lastBuildDurationNanos());
            s.lastPublishLatencyNanosMax = Math.max(s.lastPublishLatencyNanosMax, st.lastPublishLatencyNanos());
        }
        s.maxRepairPendingAgeMs = nowMs;
        s.dirtyPartitions = s.repairPendingPartitions;
        s.applierBacklog = resources.applier.backlog();
        s.appliedTotal = applied.sum();
        s.applyLatencyNanosTotal = applyLatencyNanos.sum();
        s.applyLatencyNanosMax = applyLatencyMax.get();
        s.applierOverflowsTotal = applierOverflows.sum();
        s.applyErrorsTotal = applyErrors.sum();
        s.unsupportedKeysTotal = unsupportedKeys.sum();
        s.engine = definition.legacyType().name();
        s.dimension = definition.spec().dimension();
        s.indexMemoryEstimateBytes = s.liveVectors * bytesPerVectorEstimate();
        Runtime rt = Runtime.getRuntime();
        s.heapUsedBytes = rt.totalMemory() - rt.freeMemory();
        s.heapMaxBytes = rt.maxMemory();
        s.uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        return s;
    }

    /**
     * Оценка heap на вектор — формула, не замер (П7): одна копия {@code float[]} (JVector
     * оборачивает массив без копирования), ссылки базы и, для JVector, рёбра графа.
     */
    private long bytesPerVectorEstimate() {
        long vector = 16L + (long) definition.spec().dimension() * Float.BYTES;
        long base = vector + 64;
        return definition.legacyType() == IndexType.JVECTOR_INDEX ? base + 2L * 32 * Integer.BYTES : base;
    }

    /** Для тестов: runtime партиции текущего эпизода. */
    PartitionRuntime<Object> runtime(int partition) {
        return runtimes.get(partition);
    }

    List<Integer> partitions() {
        return new ArrayList<>(runtimes.keySet());
    }
}
