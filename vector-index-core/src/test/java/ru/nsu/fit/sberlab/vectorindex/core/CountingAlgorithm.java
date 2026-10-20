package ru.nsu.fit.sberlab.vectorindex.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import ru.nsu.fit.sberlab.vectorindex.core.algorithm.AlgorithmSearchParams;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.BuildContext;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.CandidateConsumer;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.FrozenVectorView;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.OrdinalFilter;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.SearchStructure;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.VectorAlgorithm;
import ru.nsu.fit.sberlab.vectorindex.core.flat.FlatAlgorithm;

/** Flat с учётом созданных и закрытых структур; может падать при сборке или «терять» кандидатов. */
final class CountingAlgorithm implements VectorAlgorithm {

    final List<Tracked> built = new ArrayList<>();
    volatile boolean failNextBuild;
    volatile boolean returnNoCandidates;

    @Override public String name() { return "counting-flat"; }

    @Override public AlgorithmSearchParams defaultSearchParams(int k) {
        return FlatAlgorithm.INSTANCE.defaultSearchParams(k);
    }

    @Override public void validate(AlgorithmSearchParams params, int k) {
        FlatAlgorithm.INSTANCE.validate(params, k);
    }

    @Override public AlgorithmSearchParams widen(AlgorithmSearchParams params, int k) {
        return null;
    }

    @Override
    public synchronized SearchStructure build(FrozenVectorView input, BuildContext context) {
        if (failNextBuild) {
            failNextBuild = false;
            throw new IllegalStateException("simulated build failure");
        }
        Tracked tracked = new Tracked(FlatAlgorithm.INSTANCE.build(input, context), input.size());
        built.add(tracked);
        return tracked;
    }

    synchronized Tracked last() {
        return built.get(built.size() - 1);
    }

    final class Tracked implements SearchStructure {
        final SearchStructure delegate;
        final int size;
        final AtomicBoolean closed = new AtomicBoolean();

        Tracked(SearchStructure delegate, int size) {
            this.delegate = delegate;
            this.size = size;
        }

        @Override
        public Completion search(float[] q, int k, AlgorithmSearchParams p, OrdinalFilter accept,
                                 Budget budget, CandidateConsumer out) {
            if (closed.get()) {
                throw new IllegalStateException("search on a closed structure");
            }
            if (returnNoCandidates) {
                return Completion.COMPLETE;
            }
            return delegate.search(q, k, p, accept, budget, out);
        }

        @Override public long estimatedBytes() { return delegate.estimatedBytes(); }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) {
                throw new IllegalStateException("structure closed twice");
            }
            delegate.close();
        }
    }
}
