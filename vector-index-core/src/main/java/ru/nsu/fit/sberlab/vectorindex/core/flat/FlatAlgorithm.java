package ru.nsu.fit.sberlab.vectorindex.core.flat;

import ru.nsu.fit.sberlab.vectorindex.core.Budget;
import ru.nsu.fit.sberlab.vectorindex.core.Completion;
import ru.nsu.fit.sberlab.vectorindex.core.VectorSpec;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.AlgorithmSearchParams;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.BuildContext;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.CandidateConsumer;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.FrozenVectorView;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.OrdinalFilter;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.SearchStructure;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.TopOrdinals;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.VectorAlgorithm;

/** Точный перебор как обычный алгоритм ядра: «сборка» — только упаковка слоёв в базу. */
public final class FlatAlgorithm implements VectorAlgorithm {

    public static final FlatAlgorithm INSTANCE = new FlatAlgorithm();

    private static final int BUDGET_CHECK_MASK = 1023;

    private FlatAlgorithm() {
    }

    @Override
    public String name() {
        return "flat";
    }

    @Override
    public AlgorithmSearchParams defaultSearchParams(int k) {
        return FlatSearchParams.INSTANCE;
    }

    @Override
    public void validate(AlgorithmSearchParams params, int k) {
        if (params != null && !(params instanceof FlatSearchParams)) {
            throw new IllegalArgumentException("flat index does not accept " + params.getClass().getSimpleName());
        }
    }

    @Override
    public AlgorithmSearchParams widen(AlgorithmSearchParams params, int k) {
        return null; // перебор уже полный
    }

    @Override
    public SearchStructure build(FrozenVectorView input, BuildContext context) {
        float[][] vectors = new float[input.size()][];
        for (int i = 0; i < vectors.length; i++) {
            vectors[i] = input.vector(i);
        }
        return new FlatStructure(input.spec(), vectors);
    }

    private static final class FlatStructure implements SearchStructure {
        private final VectorSpec spec;
        private final float[][] vectors;

        FlatStructure(VectorSpec spec, float[][] vectors) {
            this.spec = spec;
            this.vectors = vectors;
        }

        @Override
        public Completion search(float[] query, int k, AlgorithmSearchParams params,
                                 OrdinalFilter accept, Budget budget, CandidateConsumer out) {
            if (budget.exhausted()) {
                return Completion.BUDGET_EXHAUSTED;
            }
            TopOrdinals top = new TopOrdinals(k);
            for (int ordinal = 0; ordinal < vectors.length; ordinal++) {
                if ((ordinal & BUDGET_CHECK_MASK) == BUDGET_CHECK_MASK && budget.exhausted()) {
                    return Completion.BUDGET_EXHAUSTED;
                }
                if (accept.accept(ordinal)) {
                    top.offer(ordinal, spec.distance(query, vectors[ordinal]));
                }
            }
            top.forEach(out);
            return Completion.COMPLETE;
        }

        @Override
        public long estimatedBytes() {
            return 16L + 8L * vectors.length; // только ссылки: векторы принадлежат базе ядра
        }

        @Override
        public void close() {
        }
    }
}
