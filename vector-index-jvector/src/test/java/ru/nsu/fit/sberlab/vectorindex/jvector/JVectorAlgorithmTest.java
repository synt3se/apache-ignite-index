package ru.nsu.fit.sberlab.vectorindex.jvector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import ru.nsu.fit.sberlab.vectorindex.core.Hit;
import ru.nsu.fit.sberlab.vectorindex.core.Metric;
import ru.nsu.fit.sberlab.vectorindex.core.SearchParams;
import ru.nsu.fit.sberlab.vectorindex.core.VectorSpec;
import ru.nsu.fit.sberlab.vectorindex.core.flat.FlatSearchParams;
import ru.nsu.fit.sberlab.vectorindex.testkit.ExactModel;
import ru.nsu.fit.sberlab.vectorindex.testkit.RuntimeHarness;

/** Параметры запроса и качество ANN — отдельно от логической корректности контракта. */
@Timeout(120)
class JVectorAlgorithmTest {

    @Test
    void searchParamsAreValidatedPerAlgorithm() throws Exception {
        try (RuntimeHarness h = new RuntimeHarness(new VectorSpec(3, Metric.COSINE), JVectorRuntimeContractTest.testAlgorithm())
                .built(Map.of(1L, new float[]{1, 0, 0}, 2L, new float[]{0, 1, 0}))) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> h.searchResult(new float[]{1, 0, 0}, SearchParams.topK(2).withAlgorithm(new JVectorSearchParams(1))))
                    .withMessageContaining("ef must be >= k");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> h.searchResult(new float[]{1, 0, 0}, SearchParams.topK(1).withAlgorithm(FlatSearchParams.INSTANCE)));
            assertThat(h.searchResult(new float[]{1, 0, 0}, SearchParams.topK(1).withAlgorithm(new JVectorSearchParams(1)))
                    .hits()).extracting(Hit::key).containsExactly(1L);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new JVectorSearchParams(0));
        assertThatIllegalArgumentException().isThrownBy(() -> new JVectorBuildParams(0, 32, 1.2f, 1.2f, false, true));
    }

    @ParameterizedTest
    @EnumSource(Metric.class)
    void recallAgainstExactSearchIsHighIncludingMaskedDeltas(Metric metric) throws Exception {
        VectorSpec spec = new VectorSpec(32, metric);
        Random random = new Random(2026);
        ExactModel<Long> model = new ExactModel<>(metric, spec.dimension());
        Map<Long, float[]> source = new HashMap<>();
        for (long key = 0; key < 3_000; key++) {
            float[] vector = gaussian(random, spec.dimension());
            source.put(key, vector);
            model.upsert(key, vector);
        }
        JVectorAlgorithm algorithm = new JVectorAlgorithm(new JVectorBuildParams(16, 100, 1.2f, 1.2f, true, true), k -> 64);
        try (RuntimeHarness h = new RuntimeHarness(spec, algorithm).built(source)) {
            assertThat(recall(h, model, random)).as("built graph").isGreaterThanOrEqualTo(0.9);

            // 30% удалений и 10% обновлений поверх графа: маска отсекает часть вершин.
            for (long key = 0; key < 3_000; key++) {
                int roll = random.nextInt(10);
                if (roll < 3) {
                    h.runtime.delete(key);
                    model.delete(key);
                } else if (roll == 3) {
                    float[] vector = gaussian(random, spec.dimension());
                    h.runtime.upsert(key, vector);
                    model.upsert(key, vector);
                }
            }
            assertThat(recall(h, model, random)).as("graph with masked delta").isGreaterThanOrEqualTo(0.9);
        }
    }

    private static double recall(RuntimeHarness h, ExactModel<Long> model, Random random) {
        int k = 10;
        int found = 0;
        int queries = 50;
        for (int q = 0; q < queries; q++) {
            float[] query = gaussian(random, model.snapshot().values().iterator().next().length);
            List<Hit<Long>> hits = h.search(query, k);
            model.assertValidHits(query, hits);
            Set<Long> expected = new HashSet<>(model.topK(query, k));
            for (Hit<Long> hit : hits) {
                if (expected.contains(hit.key())) found++;
            }
        }
        return found / (double) (queries * k);
    }

    private static float[] gaussian(Random random, int dimension) {
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            vector[i] = (float) random.nextGaussian();
        }
        return vector;
    }
}
