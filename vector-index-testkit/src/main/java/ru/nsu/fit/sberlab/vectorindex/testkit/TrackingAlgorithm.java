package ru.nsu.fit.sberlab.vectorindex.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import ru.nsu.fit.sberlab.vectorindex.core.Budget;
import ru.nsu.fit.sberlab.vectorindex.core.Completion;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.AlgorithmSearchParams;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.BuildContext;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.CandidateConsumer;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.FrozenVectorView;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.OrdinalFilter;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.SearchStructure;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.VectorAlgorithm;

/**
 * Обёртка любого алгоритма для проверок протокола: учёт построенных и закрытых структур,
 * управляемый отказ сборки. Поиск делегируется без изменений.
 */
public final class TrackingAlgorithm implements VectorAlgorithm {

    private final VectorAlgorithm delegate;
    private final List<Tracked> built = new ArrayList<>();
    private final AtomicInteger failuresToInject = new AtomicInteger();
    private volatile RuntimeException failure = new IllegalStateException("simulated build failure");
    private volatile Error fatal;
    private volatile Runnable onBuild;

    public TrackingAlgorithm(VectorAlgorithm delegate) {
        this.delegate = delegate;
    }

    public void failNextBuilds(int count) {
        failuresToInject.set(count);
    }

    public void failNextBuildWith(Error error) {
        fatal = error;
    }

    /** Выполнится один раз в начале следующей сборки — например, параллельные записи. */
    public void onNextBuild(Runnable action) {
        onBuild = action;
    }

    public synchronized List<Tracked> built() {
        return List.copyOf(built);
    }

    public synchronized Tracked last() {
        return built.get(built.size() - 1);
    }

    public synchronized long openStructures() {
        return built.stream().filter(t -> !t.closed()).count();
    }

    @Override public String name() { return delegate.name(); }

    @Override public AlgorithmSearchParams defaultSearchParams(int k) { return delegate.defaultSearchParams(k); }

    @Override public void validate(AlgorithmSearchParams params, int k) { delegate.validate(params, k); }

    @Override public AlgorithmSearchParams widen(AlgorithmSearchParams params, int k) { return delegate.widen(params, k); }

    @Override
    public SearchStructure build(FrozenVectorView input, BuildContext context) throws Exception {
        Runnable action = onBuild;
        if (action != null) {
            onBuild = null;
            action.run();
        }
        Error error = fatal;
        if (error != null) {
            fatal = null;
            throw error;
        }
        if (failuresToInject.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw failure;
        }
        Tracked tracked = new Tracked(delegate.build(input, context), input.size());
        synchronized (this) {
            built.add(tracked);
        }
        return tracked;
    }

    public static final class Tracked implements SearchStructure {
        private final SearchStructure delegate;
        private final int size;
        private final AtomicBoolean closed = new AtomicBoolean();

        Tracked(SearchStructure delegate, int size) {
            this.delegate = delegate;
            this.size = size;
        }

        public boolean closed() {
            return closed.get();
        }

        public int size() {
            return size;
        }

        @Override
        public Completion search(float[] query, int k, AlgorithmSearchParams params, OrdinalFilter accept,
                                 Budget budget, CandidateConsumer out) {
            if (closed.get()) {
                throw new IllegalStateException("search on a closed structure");
            }
            return delegate.search(query, k, params, accept, budget, out);
        }

        @Override public long estimatedBytes() { return delegate.estimatedBytes(); }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                throw new IllegalStateException("structure closed twice");
            }
            delegate.close();
        }
    }
}
