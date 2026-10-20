package ru.nsu.fit.sberlab.vectorindex.core;

import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import ru.nsu.fit.sberlab.vectorindex.core.algorithm.AlgorithmSearchParams;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.OrdinalFilter;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.VectorAlgorithm;

/** {@link ReadView} над одним неизменяемым {@link State}; база удерживается до close(). */
final class StateView<K> implements ReadView<K> {

    private final PartitionRuntime<K> runtime;
    private final State state;
    private final AtomicBoolean closed = new AtomicBoolean();

    StateView(PartitionRuntime<K> runtime, State state) {
        this.runtime = runtime;
        this.state = state;
    }

    @Override
    public Phase phase() {
        return state.phase;
    }

    @Override
    public RuntimeStatus status() {
        return runtime.statusOf(state);
    }

    @Override
    public long size() {
        return state.liveCount;
    }

    @Override
    public float[] get(K key) {
        ensureOpen();
        float[] vector = state.resolve(key);
        return vector == null ? null : vector.clone();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void forEachLive(BiConsumer<? super K, float[]> action) {
        ensureOpen();
        long baseRevision = state.base.revision;
        Set<Object> decided = new HashSet<>();
        state.active.entries.forEach((key, entry) -> {
            if (entry.revision() > baseRevision) {
                decided.add(key);
                if (entry.live()) {
                    action.accept((K) key, entry.vector().clone());
                }
            }
        });
        FrozenGroup.Resolution frozen = state.group.resolution();
        frozen.effective().forEach((key, entry) -> {
            if (decided.add(key) && entry.live()) {
                action.accept((K) key, entry.vector().clone());
            }
        });
        BaseSegment base = state.base;
        for (int ordinal = 0; ordinal < base.size(); ordinal++) {
            Object key = base.keys[ordinal];
            if (!decided.contains(key)) {
                action.accept((K) key, base.vectors[ordinal].clone());
            }
        }
    }

    @Override
    public LocalSearchResult<K> search(float[] query, SearchParams params) {
        ensureOpen();
        VectorAlgorithm algorithm = runtime.algorithm();
        float[] q = runtime.spec().prepareQuery(query);
        int k = params.k();
        BaseSegment base = state.base;
        boolean useStructure = !params.exact() && base.optimized();
        AlgorithmSearchParams algorithmParams = params.algorithm();
        if (algorithmParams != null || useStructure) {
            if (algorithmParams == null) {
                algorithmParams = algorithm.defaultSearchParams(Math.max(k, 1));
            }
            algorithm.validate(algorithmParams, Math.max(k, 1));
        }
        if (k <= 0) {
            return new LocalSearchResult<>(Completion.COMPLETE, List.of(), false);
        }
        // Каждый этап начинается только в бюджете: циклы проверяют его перед первым элементом
        // и далее раз в 1024 элемента. Начатый этап может выйти за бюджет не больше чем на
        // интервал проверки или один неделимый обход структуры (ADR-7).
        Budget budget = params.budget();
        if (budget.exhausted()) {
            return LocalSearchResult.exhausted();
        }

        // 0. Разрешение frozen-слоёв: обычно прогрето при capture, иначе — за счёт этого запроса.
        FrozenGroup.Resolution frozen = state.group.resolution(budget);
        if (frozen == null) {
            return LocalSearchResult.exhausted();
        }
        TopHits top = new TopHits(k);
        OrdinalMask mask = frozen.mask();
        long baseRevision = base.revision;

        // 1. Действующие записи active: точно, и маска перекрытых ordinals базы.
        if (!state.active.isEmpty()) {
            OrdinalMask activeMask = base.size() == 0 ? null
                    : (mask == null ? new OrdinalMask(base.size()) : mask.copy());
            int[] visited = {0};
            boolean finished = state.active.entries.forEachWhile((key, entry) -> {
                if ((visited[0]++ & Budget.CHECK_MASK) == 0 && budget.exhausted()) {
                    return false;
                }
                if (entry.revision() <= baseRevision) {
                    return true;
                }
                if (activeMask != null) {
                    int ordinal = base.ordinalOf(key);
                    if (ordinal >= 0) {
                        activeMask.set(ordinal);
                    }
                }
                if (entry.live()) {
                    top.offer(key, runtime.spec().distance(q, entry.vector()));
                }
                return true;
            });
            if (!finished) {
                return LocalSearchResult.exhausted();
            }
            if (activeMask != null) {
                mask = activeMask;
            }
        }

        // 2. Живые действующие записи frozen, не перекрытые active.
        for (int i = 0; i < frozen.liveKeys().length; i++) {
            if ((i & Budget.CHECK_MASK) == 0 && budget.exhausted()) {
                return LocalSearchResult.exhausted();
            }
            Object key = frozen.liveKeys()[i];
            Entry upper = state.active.get(key);
            if (upper != null && upper.revision() > baseRevision) {
                continue;
            }
            top.offer(key, runtime.spec().distance(q, frozen.liveVectors()[i]));
        }

        // 3. База: ANN с маской и точный rerank, либо точный перебор.
        int baseLive = base.size() - (mask == null ? 0 : mask.cardinality());
        boolean underfilled = false;
        if (baseLive > 0) {
            if (budget.exhausted()) {
                return LocalSearchResult.exhausted(); // не полагаемся на проверку внутри алгоритма
            }
            OrdinalFilter accept = mask == null ? OrdinalFilter.ALL : mask;
            if (!useStructure) {
                if (!exactScan(base, accept, null, q, top, budget)) {
                    return LocalSearchResult.exhausted();
                }
            } else {
                BitSet seen = new BitSet(base.size());
                int want = Math.min(k, baseLive);
                int found = annSearch(base, algorithmParams, accept, seen, q, k, top, budget);
                if (found < 0) {
                    return LocalSearchResult.exhausted();
                }
                if (found < want && !budget.exhausted()) {
                    AlgorithmSearchParams wider = algorithm.widen(algorithmParams, k);
                    if (wider != null) {
                        int more = annSearch(base, wider, accept, seen, q, k, top, budget);
                        if (more < 0) {
                            return LocalSearchResult.exhausted();
                        }
                        found += more;
                    }
                }
                if (found < want) {
                    if (budget.exhausted()) {
                        underfilled = true;
                        runtime.countUnderfilled();
                    } else {
                        runtime.countExactFallback();
                        if (!exactScan(base, accept, seen, q, top, budget)) {
                            return LocalSearchResult.exhausted();
                        }
                    }
                }
            }
        }
        return new LocalSearchResult<>(Completion.COMPLETE, top.sorted(), underfilled);
    }

    /** @return число новых допустимых кандидатов или {@code -1} при исчерпании бюджета */
    private int annSearch(BaseSegment base, AlgorithmSearchParams params, OrdinalFilter accept, BitSet seen,
                          float[] q, int k, TopHits top, Budget budget) {
        int[] added = {0};
        Completion completion = base.structure.search(q, k, params, accept, budget, ordinal -> {
            // Защита от алгоритма, вернувшего недопустимый или повторный ordinal.
            if (ordinal < 0 || ordinal >= base.size() || !accept.accept(ordinal) || seen.get(ordinal)) {
                return;
            }
            seen.set(ordinal);
            added[0]++;
            top.offer(base.keys[ordinal], runtime.spec().distance(q, base.vectors[ordinal]));
        });
        return completion == Completion.COMPLETE ? added[0] : -1;
    }

    private boolean exactScan(BaseSegment base, OrdinalFilter accept, BitSet skip, float[] q, TopHits top,
                              Budget budget) {
        for (int ordinal = 0; ordinal < base.size(); ordinal++) {
            if ((ordinal & Budget.CHECK_MASK) == 0 && budget.exhausted()) {
                return false;
            }
            if (accept.accept(ordinal) && (skip == null || !skip.get(ordinal))) {
                top.offer(base.keys[ordinal], runtime.spec().distance(q, base.vectors[ordinal]));
            }
        }
        return true;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            state.base.release();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("read view is closed");
        }
    }
}
