package ru.nsu.fit.sberlab.vectorindex.node.index;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.apache.ignite.Ignite;

import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexInfo;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexNodeStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexOperationResult.Outcome;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeBarrierProgress;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeSearchResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeStats;
import ru.nsu.fit.sberlab.vectorindex.common.dto.PartitionFailure;
import ru.nsu.fit.sberlab.vectorindex.core.Budget;
import ru.nsu.fit.sberlab.vectorindex.core.NanoClock;
import ru.nsu.fit.sberlab.vectorindex.node.search.NodeSearchRequest;

/**
 * Индексы одного узла: настроенные определения (статические, одинаковые на узлах), загруженные
 * адаптеры и общие ресурсы узла (пулы применения, сканов, поиска и сборок — одни на все
 * индексы, §7.7). Хранится в {@code nodeLocalMap} экземпляра Ignite, а не в статическом поле JVM.
 *
 * <p>Выгрузка ({@link #unload}) — без долговечного каталога: индекс остаётся настроенным,
 * закрываются только его подписки, runtime и задания; общие пулы закрывает {@link #close()}.
 * После рестарта узла индекс снова загружается из конфигурации.
 */
public final class NodeIndexRegistry implements AutoCloseable {

    private static final String KEY = "ru.nsu.fit.sberlab.vectorindex.registry";

    /** Что узел знает об {@code indexId}. */
    public enum Presence { LOADED, UNLOADED, UNKNOWN }

    private final Map<String, IndexDefinition> configured;
    private final List<String> order;
    private final String defaultIndexId;
    private final ConcurrentHashMap<String, IgniteIndexAdapter> loaded = new ConcurrentHashMap<>();
    private final NodeResources resources;
    private final Function<IndexDefinition, IgniteIndexAdapter> factory;
    private final String nodeId;
    private final Ignite ignite; // null без кластера (тесты)
    private volatile boolean closed;

    NodeIndexRegistry(List<IndexDefinition> definitions, NodeResources resources,
                      Function<IndexDefinition, IgniteIndexAdapter> factory, String nodeId, Ignite ignite) {
        Map<String, IndexDefinition> byId = new LinkedHashMap<>();
        for (IndexDefinition definition : definitions) {
            if (byId.putIfAbsent(definition.indexId(), definition) != null) {
                throw new IllegalArgumentException("duplicate index name: " + definition.indexId());
            }
        }
        if (byId.isEmpty()) {
            throw new IllegalArgumentException("no indexes configured");
        }
        this.configured = Map.copyOf(byId);
        this.resources = Objects.requireNonNull(resources, "resources");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.nodeId = nodeId;
        this.ignite = ignite;
        this.defaultIndexId = byId.containsKey("vectors") ? "vectors" : byId.keySet().iterator().next();
        this.order = List.copyOf(byId.keySet());
    }

    /** Создаёт общие ресурсы, загружает все настроенные индексы и регистрируется в {@code nodeLocalMap}. */
    public static NodeIndexRegistry start(Ignite ignite, List<IndexDefinition> definitions) {
        NodeResources resources = NodeResources.create("vindex");
        NodeIndexRegistry registry = load(definitions, resources,
                definition -> new IgniteIndexAdapter(ignite, definition, resources),
                String.valueOf(ignite.cluster().localNode().consistentId()), ignite);
        ignite.cluster().nodeLocalMap().put(KEY, registry);
        return registry;
    }

    /**
     * Создаёт реестр и загружает все индексы; при любой ошибке запуска откатывается полностью:
     * останавливает все созданные адаптеры, включая частично запущенный (его подписки и
     * периодические задания), и закрывает общие ресурсы. Наружу уходит исходная ошибка.
     */
    static NodeIndexRegistry load(List<IndexDefinition> definitions, NodeResources resources,
                                  Function<IndexDefinition, IgniteIndexAdapter> factory, String nodeId, Ignite ignite) {
        NodeIndexRegistry registry;
        try {
            registry = new NodeIndexRegistry(definitions, resources, factory, nodeId, ignite);
        } catch (RuntimeException | Error e) {
            resources.close();
            throw e;
        }
        try {
            registry.loadAll();
        } catch (RuntimeException | Error e) {
            try {
                registry.close();
            } catch (RuntimeException cleanupFailed) {
                e.addSuppressed(cleanupFailed);
            }
            throw e;
        }
        return registry;
    }

    /** Реестр узла или {@code null}, если индексная плоскость на нём не запущена. */
    public static NodeIndexRegistry of(Ignite ignite) {
        return ignite == null ? null : (NodeIndexRegistry) ignite.cluster().nodeLocalMap().get(KEY);
    }

    private void loadAll() {
        for (String indexId : order) {
            IgniteIndexAdapter adapter = factory.apply(configured.get(indexId));
            // До start: если запуск упадёт на полпути, close() остановит и этот адаптер.
            loaded.put(indexId, adapter);
            adapter.start();
        }
    }

    public String nodeId() {
        return nodeId;
    }

    /** Индекс по умолчанию для совместимых путей ({@code /vectors/*}): {@code vectors} или первый настроенный. */
    public String defaultIndexId() {
        return defaultIndexId;
    }

    /** Настроенное определение или {@code null}, если узел не знает такого индекса. */
    public IndexDefinition definition(String indexId) {
        return indexId == null ? null : configured.get(indexId);
    }

    /** Загруженный адаптер или {@code null}. */
    public IgniteIndexAdapter adapter(String indexId) {
        return indexId == null ? null : loaded.get(indexId);
    }

    public Presence presence(String indexId) {
        if (definition(indexId) == null) {
            return Presence.UNKNOWN;
        }
        return loaded.containsKey(indexId) ? Presence.LOADED : Presence.UNLOADED;
    }

    public List<IndexInfo> indexes() {
        List<IndexInfo> out = new ArrayList<>(order.size());
        for (String indexId : order) {
            out.add(configured.get(indexId).info(loaded.containsKey(indexId)));
        }
        return out;
    }

    public Collection<IgniteIndexAdapter> loadedAdapters() {
        return List.copyOf(loaded.values());
    }

    /**
     * Выгружает индекс на этом узле: снимает подписки, закрывает его runtime (поздние сборки
     * отклоняет ядро) и периодические задания. Другие индексы и общие пулы не затрагиваются.
     */
    public Outcome unload(String indexId) {
        if (definition(indexId) == null) {
            return Outcome.UNKNOWN_INDEX;
        }
        IgniteIndexAdapter adapter = loaded.remove(indexId);
        if (adapter == null) {
            return Outcome.ALREADY_UNLOADED;
        }
        adapter.stop();
        return Outcome.DONE;
    }

    /** Полная сверка одного индекса (и выход из bulk-паузы). */
    public Outcome rebuild(String indexId) {
        if (definition(indexId) == null) {
            return Outcome.UNKNOWN_INDEX;
        }
        IgniteIndexAdapter adapter = loaded.get(indexId);
        if (adapter == null) {
            throw new IllegalStateException("index '" + indexId + "' is unloaded on this node");
        }
        adapter.resumeIndexing();
        return Outcome.DONE;
    }

    /**
     * Поиск узла по запросу координатора. Неизвестный индекс, выгруженный индекс и другое
     * определение с тем же {@code indexId} отклоняют все назначенные партиции с явной причиной —
     * такой ответ не может стать {@code COMPLETE}.
     */
    public NodeSearchResult search(NodeSearchRequest request) {
        IndexDefinition definition = definition(request.indexId());
        if (definition == null) {
            return NodeSearchResult.rejected(request.partitions(), PartitionFailure.Reason.NO_INDEX, nodeId);
        }
        if (!definition.fingerprint().equals(request.fingerprint())) {
            return NodeSearchResult.rejected(request.partitions(), PartitionFailure.Reason.DEFINITION_MISMATCH, nodeId);
        }
        IgniteIndexAdapter adapter = adapter(request.indexId());
        if (adapter == null) {
            return NodeSearchResult.rejected(request.partitions(), PartitionFailure.Reason.INDEX_UNLOADED, nodeId);
        }
        Budget budget = Budget.remaining(NanoClock.SYSTEM, request.budgetNanos());
        return adapter.search(request.partitions(), request.query(), request.k(),
                definition.searchParams(request.params(), request.k()), request.exact(), budget);
    }

    /** Начало барьера загрузки на узле; отказ (неизвестный, выгруженный, другое определение, таймаут) — в {@code error}. */
    public NodeBarrierProgress beginBarrier(String indexId, String fingerprint, String barrierId, boolean optimize,
                                           long drainTimeoutNanos) {
        NodeBarrierProgress refused = barrierRefusal(indexId, fingerprint);
        if (refused != null) {
            return refused;
        }
        try {
            return adapter(indexId).beginBarrier(barrierId, optimize, drainTimeoutNanos);
        } catch (IllegalStateException notQuiet) {
            return barrierError(notQuiet.getMessage());
        }
    }

    /** Продвижение барьера на узле (идемпотентно). */
    public NodeBarrierProgress barrierProgress(String indexId, String fingerprint, String barrierId) {
        NodeBarrierProgress refused = barrierRefusal(indexId, fingerprint);
        return refused != null ? refused : adapter(indexId).barrierProgress(barrierId);
    }

    private NodeBarrierProgress barrierRefusal(String indexId, String fingerprint) {
        IndexDefinition definition = definition(indexId);
        if (definition == null) {
            return barrierError(PartitionFailure.Reason.NO_INDEX.name());
        }
        if (!definition.fingerprint().equals(fingerprint)) {
            return barrierError(PartitionFailure.Reason.DEFINITION_MISMATCH.name());
        }
        return adapter(indexId) == null ? barrierError(PartitionFailure.Reason.INDEX_UNLOADED.name()) : null;
    }

    private NodeBarrierProgress barrierError(String error) {
        NodeBarrierProgress progress = new NodeBarrierProgress();
        progress.nodeId = nodeId;
        progress.error = error;
        return progress;
    }

    /** Подготовка к очистке кэша индекса: пауза, завершение сверки и применений, закрытие runtime. */
    public Outcome suspendForTruncate(String indexId, long pauseTtlMs, long timeoutNanos) {
        IgniteIndexAdapter adapter = adapterOrThrow(indexId);
        if (adapter == null) {
            return Outcome.UNKNOWN_INDEX;
        }
        adapter.suspendForTruncate(pauseTtlMs, timeoutNanos);
        return Outcome.DONE;
    }

    /** После очистки кэша: выход из паузы и полная сверка (новые runtime). */
    public Outcome resumeAfterTruncate(String indexId) {
        IgniteIndexAdapter adapter = adapterOrThrow(indexId);
        if (adapter == null) {
            return Outcome.UNKNOWN_INDEX;
        }
        adapter.resumeIndexing();
        return Outcome.DONE;
    }

    /** {@code null} — индекс неизвестен; выгруженный — {@link IllegalStateException}. */
    private IgniteIndexAdapter adapterOrThrow(String indexId) {
        if (definition(indexId) == null) {
            return null;
        }
        IgniteIndexAdapter adapter = loaded.get(indexId);
        if (adapter == null) {
            throw new IllegalStateException("index '" + indexId + "' is unloaded on this node");
        }
        return adapter;
    }

    /** Готовность индекса на узле, включая выгруженный и неизвестный. */
    public IndexNodeStatus status(String indexId) {
        IgniteIndexAdapter adapter = adapter(indexId);
        if (adapter != null) {
            return adapter.localStatus();
        }
        IndexNodeStatus status = new IndexNodeStatus();
        status.nodeId = nodeId;
        status.indexId = indexId;
        IndexDefinition definition = definition(indexId);
        if (definition == null) {
            status.state = IndexNodeStatus.State.UNKNOWN_INDEX;
        } else {
            status.state = IndexNodeStatus.State.UNLOADED;
            status.fingerprint = definition.fingerprint();
            status.algorithm = definition.kind().name();
            status.metric = definition.spec().metric().name();
            status.dimension = definition.spec().dimension();
        }
        return status;
    }

    /**
     * Статистика узла: общие ресурсы — по одному значению на узел, поля индекса — индекс по
     * умолчанию (совместимость), все индексы — в {@code indexes}.
     */
    public NodeStats nodeStats() {
        IgniteIndexAdapter defaultAdapter = loaded.get(defaultIndexId);
        NodeStats stats = defaultAdapter != null ? defaultAdapter.localStats() : new NodeStats();
        stats.nodeId = nodeId;
        stats.indexId = defaultIndexId;
        stats.indexes = new ArrayList<>();
        for (String indexId : order) {
            stats.indexes.add(status(indexId));
        }
        stats.applierBacklog = resources.applier.backlog();
        Runtime rt = Runtime.getRuntime();
        stats.heapUsedBytes = rt.totalMemory() - rt.freeMemory();
        stats.heapMaxBytes = rt.maxMemory();
        stats.uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        return stats;
    }

    /** Останавливает все индексы узла и закрывает общие ресурсы. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (ignite != null) {
            try {
                ignite.cluster().nodeLocalMap().remove(KEY, this);
            } catch (IllegalStateException nodeAlreadyStopped) {
                // nodeLocalMap ушёл вместе с узлом
            }
        }
        // Ошибка остановки одного индекса не оставляет запущенными остальные и общие пулы.
        RuntimeException failure = null;
        for (String indexId : order) {
            IgniteIndexAdapter adapter = loaded.remove(indexId);
            if (adapter != null) {
                try {
                    adapter.stop();
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
        }
        resources.close();
        if (failure != null) {
            throw failure;
        }
    }
}
