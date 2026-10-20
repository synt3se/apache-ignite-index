package ru.nsu.fit.sberlab.vectorindex.jvector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.IntUnaryOperator;

import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.graph.RandomAccessVectorValues;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.graph.similarity.BuildScoreProvider;
import io.github.jbellis.jvector.graph.similarity.DefaultSearchScoreProvider;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import ru.nsu.fit.sberlab.vectorindex.core.Budget;
import ru.nsu.fit.sberlab.vectorindex.core.Completion;
import ru.nsu.fit.sberlab.vectorindex.core.Metric;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.AlgorithmSearchParams;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.BuildContext;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.CandidateConsumer;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.FrozenVectorView;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.OrdinalFilter;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.SearchStructure;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.VectorAlgorithm;

/**
 * Адаптер JVector: только построение графа по ordinals стабильного входа и поиск кандидатов.
 * Источник данных, дельта, удаления и публикация — ядро.
 *
 * <p>COSINE строится как DOT_PRODUCT: ядро нормализует векторы при приёме, порядок тот же,
 * умножений втрое меньше. SQUARED_L2 — EUCLIDEAN. Оценки JVector наружу не выходят:
 * расстояние ответа считает ядро по единому контракту.
 */
public final class JVectorAlgorithm implements VectorAlgorithm {

    private static final VectorTypeSupport VECTORS = VectorizationProvider.getInstance().getVectorTypeSupport();
    private static final int MAX_EF = 1 << 20;

    private final JVectorBuildParams build;
    private final IntUnaryOperator defaultEf;

    /** @param defaultEf ширина поиска по умолчанию для {@code k}, если запрос её не задал */
    public JVectorAlgorithm(JVectorBuildParams build, IntUnaryOperator defaultEf) {
        this.build = Objects.requireNonNull(build, "build");
        this.defaultEf = Objects.requireNonNull(defaultEf, "defaultEf");
    }

    /** Совместимость со старой настройкой {@code efSearch}: ширина = k · multiplier. */
    public static JVectorAlgorithm withEfMultiplier(JVectorBuildParams build, int multiplier) {
        int factor = Math.max(1, multiplier);
        return new JVectorAlgorithm(build, k -> (int) Math.min(MAX_EF, (long) k * factor));
    }

    public JVectorBuildParams buildParams() {
        return build;
    }

    @Override
    public String name() {
        return "jvector";
    }

    @Override
    public AlgorithmSearchParams defaultSearchParams(int k) {
        return new JVectorSearchParams(Math.max(k, defaultEf.applyAsInt(k)));
    }

    @Override
    public void validate(AlgorithmSearchParams params, int k) {
        if (!(params instanceof JVectorSearchParams jvector)) {
            throw new IllegalArgumentException("jvector index requires JVectorSearchParams, got "
                    + (params == null ? "null" : params.getClass().getSimpleName()));
        }
        if (jvector.ef() < k) {
            throw new IllegalArgumentException("ef must be >= k: ef=" + jvector.ef() + ", k=" + k);
        }
    }

    @Override
    public AlgorithmSearchParams widen(AlgorithmSearchParams params, int k) {
        int ef = ((JVectorSearchParams) params).ef();
        return ef >= MAX_EF ? null : new JVectorSearchParams(Math.min(MAX_EF, Math.max(k, ef) * 2));
    }

    @Override
    public SearchStructure build(FrozenVectorView input, BuildContext context) throws IOException {
        if (context.cancelled()) {
            throw new CancellationException("build is no longer needed");
        }
        int size = input.size();
        if (size == 0) {
            return new EmptyStructure();
        }
        int dimension = input.spec().dimension();
        List<VectorFloat<?>> values = new ArrayList<>(size);
        for (int ordinal = 0; ordinal < size; ordinal++) {
            values.add(VECTORS.createFloatVector(input.vector(ordinal))); // обёртка без копии
        }
        RandomAccessVectorValues ravv = new ListRandomAccessVectorValues(values, dimension);
        VectorSimilarityFunction similarity = similarity(input.spec().metric());
        BuildScoreProvider scores = BuildScoreProvider.randomAccessScoreProvider(ravv, similarity);
        ImmutableGraphIndex graph;
        try (GraphIndexBuilder builder = new GraphIndexBuilder(scores, dimension, build.m(), build.efConstruction(),
                build.neighborOverflow(), build.alpha(), build.addHierarchy(), build.refineFinalGraph(),
                context.simdPool(), context.parallelPool())) {
            graph = builder.build(ravv);
        }
        return new GraphStructure(graph, ravv, similarity, size);
    }

    static VectorSimilarityFunction similarity(Metric metric) {
        return switch (metric) {
            case COSINE -> VectorSimilarityFunction.DOT_PRODUCT;
            case SQUARED_L2 -> VectorSimilarityFunction.EUCLIDEAN;
        };
    }

    private static final class GraphStructure implements SearchStructure {
        private final ImmutableGraphIndex graph;
        private final RandomAccessVectorValues ravv;
        private final VectorSimilarityFunction similarity;
        private final int size;

        GraphStructure(ImmutableGraphIndex graph, RandomAccessVectorValues ravv,
                       VectorSimilarityFunction similarity, int size) {
            this.graph = graph;
            this.ravv = ravv;
            this.similarity = similarity;
            this.size = size;
        }

        @Override
        public Completion search(float[] query, int k, AlgorithmSearchParams params, OrdinalFilter accept,
                                 Budget budget, CandidateConsumer out) {
            if (budget.exhausted()) {
                return Completion.BUDGET_EXHAUSTED;
            }
            int topK = Math.min(k, size);
            int rerankK = Math.max(topK, Math.min(((JVectorSearchParams) params).ef(), size));
            Bits acceptOrds = accept == OrdinalFilter.ALL ? Bits.ALL : accept::accept;
            SearchResult result;
            try (GraphSearcher searcher = new GraphSearcher(graph)) {
                // Обход графа неделим: бюджет проверен до него, его стоимость ограничена ef.
                result = searcher.search(DefaultSearchScoreProvider.exact(VECTORS.createFloatVector(query),
                        similarity, ravv), topK, rerankK, 0.0f, 0.0f, acceptOrds);
            } catch (IOException e) {
                throw new UncheckedIOException("JVector search failed", e);
            }
            for (SearchResult.NodeScore node : result.getNodes()) {
                out.accept(node.node);
            }
            return Completion.COMPLETE;
        }

        @Override
        public long estimatedBytes() {
            return graph.ramBytesUsed() + 32L * size;
        }

        @Override
        public void close() {
            try {
                graph.close();
            } catch (IOException e) {
                throw new UncheckedIOException("failed to close JVector graph", e);
            }
        }
    }

    private static final class EmptyStructure implements SearchStructure {
        @Override
        public Completion search(float[] query, int k, AlgorithmSearchParams params, OrdinalFilter accept,
                                 Budget budget, CandidateConsumer out) {
            return budget.exhausted() ? Completion.BUDGET_EXHAUSTED : Completion.COMPLETE;
        }

        @Override public long estimatedBytes() { return 0; }

        @Override public void close() { }
    }
}
