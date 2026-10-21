package ru.nsu.fit.sberlab.vectorindex.node.index;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
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

import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexHit;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexNodeStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.KeyType;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeBarrierProgress;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeSearchResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeStats;
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
    private static final int MAX_BARRIERS = 16;

    /**
     * Барьер на узле: экземпляры runtime и тикеты сверки, выданные после завершения принятых
     * применений, и версия топологии в этот момент.
     */
    private record Barrier(long topologyVersion, boolean optimize, Map<Integer, PartitionRuntime<Object>> runtimes,
                           Map<Integer, Long> tickets) {
    }

    private final IndexDefinition definition;
    private final VectorSource source;
    private final NodeResources resources;
    private final NanoClock clock;
    private final IgniteLogger log;
    private final Ignite ignite;           // null без кластера (тесты)
    private final String nodeId;

    private final ConcurrentHashMap<Integer, PartitionRuntime<Object>> runtimes = new ConcurrentHashMap<>();
    /** Runtime (по идентичности), чей скан стоит в очереди или выполняется: задание — эпизода, не номера партиции. */
    private final Set<PartitionRuntime<Object>> scanQueued = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean reconcileDebounce = new AtomicBoolean();
    private final AtomicBoolean reconcileRunning = new AtomicBoolean();
    /** Запрошенная, но ещё не выполненная сверка (и полная ли): запрос не теряется, если сверка уже идёт. */
    private final AtomicBoolean reconcileWanted = new AtomicBoolean();
    private final AtomicBoolean rebuildWanted = new AtomicBoolean();
    /** Барьеры загрузки этого индекса на узле (последние {@value #MAX_BARRIERS}). */
    private final Map<String, Barrier> barriers = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Barrier> eldest) {
                    return size() > MAX_BARRIERS;
                }
            });
    private volatile boolean stopped;
    private volatile long bulkPauseUntilMs;

    private final LongAdder applied = new LongAdder();
    private final AtomicLong pendingApplies = new AtomicLong(); // принятые полосой и ещё не завершённые
    private final LongAdder applyLatencyNanos = new LongAdder();
    private final AtomicLong applyLatencyMax = new AtomicLong();
    private final LongAdder applierOverflows = new LongAdder();
    private final LongAdder applyErrors = new LongAdder();
    private final LongAdder unsupportedKeys = new LongAdder();
    private final LongAdder wrongTypeValues = new LongAdder();
    private final LongAdder noVectorValues = new LongAdder();
    private final LongAdder reconcileErrors = new LongAdder();

    private IgnitePredicate<Event> cacheListener;
    private IgnitePredicate<Event> topologyListener;
    private final List<ScheduledFuture<?>> housekeeping = new ArrayList<>();

    /** Индекс узла на общих ресурсах реестра ({@link NodeIndexRegistry}); ресурсами не владеет. */
    IgniteIndexAdapter(Ignite ignite, IndexDefinition definition, NodeResources resources) {
        this(definition, new IgniteVectorSource(ignite, definition.cacheName(), definition.field()), resources,
                NanoClock.SYSTEM, ignite.log(), ignite, String.valueOf(ignite.cluster().localNode().consistentId()));
    }

    /** Без кластера: источник, пулы и часы внедряются (тесты). */
    IgniteIndexAdapter(IndexDefinition definition, VectorSource source, NodeResources resources,
                       NanoClock clock, IgniteLogger log) {
        this(definition, source, resources, clock, log, null, "local");
    }

    private IgniteIndexAdapter(IndexDefinition definition, VectorSource source, NodeResources resources,
                               NanoClock clock, IgniteLogger log, Ignite ignite, String nodeId) {
        this.definition = definition;
        this.source = source;
        this.resources = resources;
        this.clock = clock;
        this.log = log;
        this.ignite = ignite;
        this.nodeId = nodeId;
    }

    public IndexDefinition definition() {
        return definition;
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
        }
        if (resources.housekeeper != null) {
            synchronized (housekeeping) {
                housekeeping.add(resources.housekeeper.schedule(() -> safeReconcile(true), 1, TimeUnit.SECONDS));
                housekeeping.add(resources.housekeeper.scheduleWithFixedDelay(this::housekeeping,
                        RECONCILE_INTERVAL_MS, RECONCILE_INTERVAL_MS, TimeUnit.MILLISECONDS));
            }
        }
        log.info("[vindex] adapter started: index=" + definition.indexId() + ", algorithm="
                + definition.algorithm().name() + ", visibility=eventual (cache events)");
    }

    /** Закрывает только ресурсы этого индекса: подписки, периодические задания и runtime. */
    public void stop() {
        stopped = true;
        synchronized (housekeeping) {
            housekeeping.forEach(task -> task.cancel(false));
            housekeeping.clear();
        }
        if (ignite != null) {
            try {
                if (cacheListener != null) {
                    ignite.events().stopLocalListen(cacheListener);
                }
                if (topologyListener != null) {
                    ignite.events().stopLocalListen(topologyListener);
                }
            } catch (IllegalStateException nodeAlreadyStopped) {
                // узел остановлен раньше адаптера: слушатели ушли вместе с ним
            }
        }
        for (PartitionRuntime<Object> runtime : runtimes.values()) {
            runtime.close();
        }
        runtimes.clear();
        barriers.clear();
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
        if (KeyType.of(rawKey) == null) {
            unsupportedKeys.increment(); // v1: Long, String, UUID — классы JDK на всём пути ответа
            return;
        }
        Object key = rawKey;
        int partition = source.partitionOf(key);
        PartitionRuntime<Object> runtime = runtimes.get(partition);
        if (runtime == null) {
            return; // не наша primary-партиция или runtime ещё не создан: его скан прочитает ключ
        }
        long received = clock.nanoTime();
        pendingApplies.incrementAndGet();
        if (!resources.applier.submit(partition, () -> applyKey(runtime, key, received))) {
            pendingApplies.decrementAndGet();
            applierOverflows.increment();
            runtime.requestRepair(RepairReason.APPLIER_OVERFLOW);
            log.warning("[vindex] applier queue full - partition " + partition + " needs repair");
            requestReconcile();
        }
    }

    private void applyKey(PartitionRuntime<Object> runtime, Object key, long receivedNanos) {
        try {
            ApplyResult result = apply(runtime, key, source.read(key));
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
            pendingApplies.decrementAndGet();
        }
    }

    /** Вектор — upsert; без вектора — delete; неверный тип — delete и счётчик (исключение из проекции). */
    private ApplyResult apply(PartitionRuntime<Object> runtime, Object key, SourceValue value) {
        if (value instanceof SourceValue.Vector present) {
            return runtime.upsert(key, present.vector());
        }
        if (value instanceof SourceValue.WrongType wrong) {
            wrongTypeValues.increment();
            log.warning("[vindex] index " + definition.indexId() + ": field '" + definition.field()
                    + "' of key " + key + " is " + wrong.type() + ", not float[] - excluded from the index");
        } else if (value instanceof SourceValue.NoVector) {
            noVectorValues.increment();
        }
        return runtime.delete(key);
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

    /**
     * Запрос сверки не теряется, если сверка уже идёт: он запоминается, и идущая сверка делает ещё
     * один проход (полный, если его просили). На паузе запросы не выполняются; выход из паузы
     * запрашивает полную сверку сам.
     */
    private void safeReconcile(boolean rebuildAll) {
        if (stopped || paused()) {
            return;
        }
        if (rebuildAll) {
            rebuildWanted.set(true);
        }
        reconcileWanted.set(true);
        while (reconcileWanted.get() && !paused() && reconcileRunning.compareAndSet(false, true)) {
            try {
                while (!stopped && !paused() && reconcileWanted.getAndSet(false)) {
                    boolean full = rebuildWanted.getAndSet(false);
                    try {
                        reconcile(full);
                    } catch (RuntimeException e) {
                        if (full) {
                            rebuildWanted.set(true); // полная сверка не потеряна: следующий проход повторит её
                        }
                        throw e;
                    }
                }
            } catch (RuntimeException e) {
                reconcileErrors.increment();
                log.error("[vindex] reconcile failed; will retry on the next tick", e);
            } finally {
                reconcileRunning.set(false);
            }
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
            } else if (runtime.repairPending() || runtime.phase() == Phase.LOADING) {
                // LOADING без скана в очереди — первоначальный скан не состоялся (отказ пула и т. п.)
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

    /**
     * Не больше одного скана на runtime; повтор после скана решают счётчики сверки runtime.
     * Задание прежнего эпизода той же партиции не мешает скану нового: оно завершится само.
     */
    private boolean scheduleScan(int partition, PartitionRuntime<Object> runtime) {
        if (stopped || !scanQueued.add(runtime)) {
            return false;
        }
        try {
            resources.scanPool.execute(() -> runScan(partition, runtime));
            return true;
        } catch (RejectedExecutionException shuttingDown) {
            scanQueued.remove(runtime);
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
                source.scan(partition, (key, value) -> {
                    if (KeyType.of(key) == null) {
                        unsupportedKeys.increment();
                    } else if (value instanceof SourceValue.Vector present) {
                        scan.offer(key, present.vector());
                    } else if (value instanceof SourceValue.WrongType) {
                        wrongTypeValues.increment(); // не попадает в базу скана — исключено из проекции
                    } else if (value instanceof SourceValue.NoVector) {
                        noVectorValues.increment();
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
            scanQueued.remove(runtime);
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

    /**
     * Перед очисткой кэша ({@code truncate}): события игнорируются, сверка спит, идущая сверка и
     * принятые применения завершены, все runtime закрыты — их незавершённая работа (сканы, сборки,
     * применения) отклоняется ядром и не попадёт в новое состояние. Выход — {@link #resumeIndexing()},
     * страховка — истечение паузы.
     *
     * @throws IllegalStateException сверка или применения не завершились за {@code timeoutNanos}
     */
    public void suspendForTruncate(long pauseTtlMs, long timeoutNanos) {
        pauseIndexing(pauseTtlMs);
        awaitQuiet(() -> !reconcileRunning.get() && pendingApplies.get() == 0, timeoutNanos,
                "reconcile and applies of index " + definition.indexId());
        clearAll();
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

    // ---------------------------------------------------------------- барьер загрузки (этап D)

    /**
     * Начало барьера на узле (ADR-19). Вызывающий гарантирует, что записи подтверждены и писатели
     * остановлены. Сначала дожидаемся завершения всех принятых применений: обработчик, прочитавший
     * старое значение до последних записей, иначе применил бы его после начала финального скана, и
     * его ревизия перекрыла бы прочитанное сканом. Применение, начатое после этого момента, читает
     * уже окончательное значение. Затем каждому runtime выдаётся новый тикет сверки и ставится
     * скан; уже идущий скан тикет не закрывает — после его публикации будет ещё проход.
     *
     * @throws IllegalStateException применения не завершились за {@code drainTimeoutNanos}
     */
    public NodeBarrierProgress beginBarrier(String barrierId, boolean optimize, long drainTimeoutNanos) {
        awaitQuiet(() -> pendingApplies.get() == 0, drainTimeoutNanos, "applies of index " + definition.indexId());
        long topology = source.topologyVersion();
        Map<Integer, PartitionRuntime<Object>> snapshot = new TreeMap<>(runtimes);
        Map<Integer, Long> tickets = new java.util.HashMap<>();
        snapshot.forEach((partition, runtime) -> tickets.put(partition, runtime.requestRepair(RepairReason.MANUAL)));
        barriers.put(barrierId, new Barrier(topology, optimize, Map.copyOf(snapshot), Map.copyOf(tickets)));
        snapshot.forEach(this::scheduleScan);
        return barrierProgress(barrierId);
    }

    /**
     * Продвижение барьера на узле. Идемпотентно: партиции без завершённого скана, у которых скан не
     * стоит в очереди, ставятся на скан снова; после скана при {@code optimize} принудительно
     * запрашивается сборка (в том числе для дельты ниже автоматического порога), пока не будет
     * построена структура текущего состояния с пустыми active и frozen. Для Flat и пустых партиций
     * это обычная завершённая сборка, графа не требуется.
     */
    public NodeBarrierProgress barrierProgress(String barrierId) {
        NodeBarrierProgress progress = new NodeBarrierProgress();
        progress.nodeId = nodeId;
        Barrier barrier = barriers.get(barrierId);
        if (barrier == null) {
            progress.unknownBarrier = true;
            return progress;
        }
        progress.topologyVersion = barrier.topologyVersion();
        progress.optimize = barrier.optimize();
        List<Integer> scanned = new ArrayList<>();
        List<Integer> optimized = new ArrayList<>();
        List<Integer> failedBuilds = new ArrayList<>();
        List<Integer> invalidated = new ArrayList<>();
        List<Integer> foreign = new ArrayList<>();
        for (Map.Entry<Integer, PartitionRuntime<Object>> entry : new TreeMap<>(barrier.runtimes()).entrySet()) {
            int partition = entry.getKey();
            PartitionRuntime<Object> runtime = entry.getValue();
            if (runtime.isClosed() || runtimes.get(partition) != runtime) {
                invalidated.add(partition); // смена владельца, выгрузка, очистка: этот runtime больше не текущий
                continue;
            }
            RuntimeStatus st = runtime.status();
            progress.liveVectors += st.liveCount();
            if (st.phase() != Phase.READY || st.repairCompleted() < barrier.tickets().get(partition)) {
                if (!scanQueued.contains(runtime)) {
                    scheduleScan(partition, runtime);
                }
                continue;
            }
            scanned.add(partition);
            if (!barrier.optimize()) {
                continue;
            }
            if (st.optimization() == OptimizationState.BUILT && st.deltaEntries() == 0 && st.frozenLayers() == 0
                    && !st.scanOpen()) {
                optimized.add(partition);
                continue;
            }
            if (st.optimization() == OptimizationState.FAILED) {
                failedBuilds.add(partition);
            }
            if (st.optimization() != OptimizationState.BUILDING) {
                resources.builds.requestOptimization(runtime);
            }
        }
        for (Integer partition : new TreeMap<>(runtimes).keySet()) {
            if (!barrier.runtimes().containsKey(partition)) {
                foreign.add(partition);
            }
        }
        progress.partitions = toArray(new ArrayList<>(new TreeMap<>(barrier.runtimes()).keySet()));
        progress.scannedPartitions = toArray(scanned);
        progress.optimizedPartitions = toArray(optimized);
        progress.failedBuildPartitions = toArray(failedBuilds);
        progress.invalidatedPartitions = toArray(invalidated);
        progress.foreignPartitions = toArray(foreign);
        progress.pendingApplies = pendingApplies.get();
        progress.scansInProgress = scanQueued.size();
        return progress;
    }

    private static void awaitQuiet(java.util.function.BooleanSupplier quiet, long timeoutNanos, String what) {
        long deadline = System.nanoTime() + timeoutNanos;
        while (!quiet.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) {
                throw new IllegalStateException(what + " did not finish within "
                        + TimeUnit.NANOSECONDS.toMillis(timeoutNanos) + " ms");
            }
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("interrupted while waiting for " + what);
            }
        }
    }

    // ---------------------------------------------------------------- поиск и статистика

    /**
     * Поиск по назначенным партициям ({@code null} — по всем локальным runtime).
     *
     * @throws IllegalArgumentException неверный запрос
     */
    public NodeSearchResult search(int[] partitions, float[] query, int k, AlgorithmSearchParams params,
                                   boolean exact, Budget budget) {
        definition.spec().prepareQuery(query); // неверный запрос — ошибка вызывающего, до рассылки по пулу
        int[] assigned = partitions != null ? partitions
                : runtimes.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        return LocalSearch.search(runtimes, assigned, query, k, params, exact, budget, resources.searchPool,
                nodeId, source.topologyVersion());
    }

    /** Top-k по всем локальным партициям в фазе READY (тесты и диагностика). */
    public List<IndexHit> searchLocal(float[] query, int count) {
        return search(null, query, count, null, false, Budget.unlimited()).hits;
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

    /** Готовность и метрики индекса на этом узле; общие ресурсы узла сюда не входят. */
    public IndexNodeStatus localStatus() {
        IndexNodeStatus s = new IndexNodeStatus();
        s.nodeId = nodeId;
        s.indexId = definition.indexId();
        s.state = IndexNodeStatus.State.LOADED;
        s.fingerprint = definition.fingerprint();
        s.algorithm = definition.kind().name();
        s.metric = definition.spec().metric().name();
        s.dimension = definition.spec().dimension();
        List<Integer> owned = new ArrayList<>();
        List<Integer> ready = new ArrayList<>();
        List<Integer> loading = new ArrayList<>();
        List<Integer> repair = new ArrayList<>();
        long maxRepairAgeMs = 0;
        for (Map.Entry<Integer, PartitionRuntime<Object>> entry : new TreeMap<>(runtimes).entrySet()) {
            RuntimeStatus st = entry.getValue().status();
            int partition = entry.getKey();
            owned.add(partition);
            if (st.phase() == Phase.READY) {
                ready.add(partition);
                s.liveVectors += st.liveCount();
            } else if (st.phase() == Phase.LOADING) {
                loading.add(partition);
            }
            if (st.repairPending()) {
                repair.add(partition);
                maxRepairAgeMs = Math.max(maxRepairAgeMs, TimeUnit.NANOSECONDS.toMillis(st.repairPendingAgeNanos()));
            }
            switch (st.optimization()) {
                case NOT_BUILT -> s.optimizationNotBuilt++;
                case BUILDING -> s.optimizationBuilding++;
                case BUILT -> s.optimizationBuilt++;
                case FAILED -> s.optimizationFailed++;
            }
            s.deltaEntries += st.deltaEntries();
            s.deltaLive += st.deltaLive();
            s.frozenLayers += st.frozenLayers();
            s.dedupSkippedTotal += st.dedupSkipped();
            s.buildFailuresTotal += st.buildFailures();
            s.publishRejectedTotal += st.publishRejected();
            s.rejectedVectorsTotal += st.rejectedTotal();
            s.writerLockWaitNanosMax = Math.max(s.writerLockWaitNanosMax, st.writerLockWaitNanosMax());
            s.lastBuildDurationNanosMax = Math.max(s.lastBuildDurationNanosMax, st.lastBuildDurationNanos());
            s.lastPublishLatencyNanosMax = Math.max(s.lastPublishLatencyNanosMax, st.lastPublishLatencyNanos());
        }
        s.ownedPartitions = toArray(owned);
        s.readyPartitions = toArray(ready);
        s.loadingPartitions = toArray(loading);
        s.repairPendingPartitions = toArray(repair);
        s.maxRepairPendingAgeMs = maxRepairAgeMs;
        s.pendingApplies = pendingApplies.get();
        s.scansInProgress = scanQueued.size();
        s.appliedTotal = applied.sum();
        s.applyLatencyNanosTotal = applyLatencyNanos.sum();
        s.applyLatencyNanosMax = applyLatencyMax.get();
        s.applierOverflowsTotal = applierOverflows.sum();
        s.applyErrorsTotal = applyErrors.sum();
        s.unsupportedKeysTotal = unsupportedKeys.sum();
        s.wrongTypeValuesTotal = wrongTypeValues.sum();
        s.noVectorValuesTotal = noVectorValues.sum();
        s.indexMemoryEstimateBytes = s.liveVectors * bytesPerVectorEstimate();
        return s;
    }

    /** Совместимость {@code /vectors/stats}: этот индекс и общие ресурсы узла. */
    public NodeStats localStats() {
        IndexNodeStatus st = localStatus();
        NodeStats s = new NodeStats();
        s.nodeId = nodeId;
        s.indexId = st.indexId;
        s.indexes.add(st);
        s.ownedPartitions = st.ownedPartitions.length;
        s.activePartitions = st.readyPartitions.length;
        s.loadingPartitions = st.loadingPartitions.length;
        s.repairPendingPartitions = st.repairPendingPartitions.length;
        s.dirtyPartitions = s.repairPendingPartitions;
        s.maxRepairPendingAgeMs = st.maxRepairPendingAgeMs;
        s.optimizationFailedPartitions = st.optimizationFailed;
        s.liveVectors = st.liveVectors;
        s.deltaEntries = st.deltaEntries;
        s.enginePendingVectors = st.deltaLive;
        s.frozenLayers = st.frozenLayers;
        s.dedupSkippedTotal = st.dedupSkippedTotal;
        s.buildFailuresTotal = st.buildFailuresTotal;
        s.publishRejectedTotal = st.publishRejectedTotal;
        s.rejectedVectorsTotal = st.rejectedVectorsTotal;
        s.writerLockWaitNanosMax = st.writerLockWaitNanosMax;
        s.lastBuildDurationNanosMax = st.lastBuildDurationNanosMax;
        s.lastPublishLatencyNanosMax = st.lastPublishLatencyNanosMax;
        s.appliedTotal = st.appliedTotal;
        s.applyLatencyNanosTotal = st.applyLatencyNanosTotal;
        s.applyLatencyNanosMax = st.applyLatencyNanosMax;
        s.applierOverflowsTotal = st.applierOverflowsTotal;
        s.applyErrorsTotal = st.applyErrorsTotal;
        s.unsupportedKeysTotal = st.unsupportedKeysTotal;
        s.engine = definition.kind().legacyEngineName();
        s.dimension = st.dimension;
        s.indexMemoryEstimateBytes = st.indexMemoryEstimateBytes;
        s.applierBacklog = resources.applier.backlog();
        Runtime rt = Runtime.getRuntime();
        s.heapUsedBytes = rt.totalMemory() - rt.freeMemory();
        s.heapMaxBytes = rt.maxMemory();
        s.uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        return s;
    }

    private static int[] toArray(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Оценка heap на вектор — формула, не замер (П7): одна копия {@code float[]} (JVector
     * оборачивает массив без копирования), ссылки базы и, для JVector, рёбра графа.
     */
    private long bytesPerVectorEstimate() {
        long vector = 16L + (long) definition.spec().dimension() * Float.BYTES;
        long base = vector + 64;
        return definition.kind() == AlgorithmKind.JVECTOR ? base + 2L * 32 * Integer.BYTES : base;
    }

    /** Для тестов: runtime партиции текущего эпизода. */
    PartitionRuntime<Object> runtime(int partition) {
        return runtimes.get(partition);
    }

    List<Integer> partitions() {
        return new ArrayList<>(runtimes.keySet());
    }
}
