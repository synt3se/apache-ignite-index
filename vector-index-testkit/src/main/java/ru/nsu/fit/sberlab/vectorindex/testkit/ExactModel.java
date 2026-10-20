package ru.nsu.fit.sberlab.vectorindex.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.nsu.fit.sberlab.vectorindex.core.Hit;
import ru.nsu.fit.sberlab.vectorindex.core.Metric;
import ru.nsu.fit.sberlab.vectorindex.core.ReadView;

/**
 * Независимая точная модель логического содержимого индекса: обычная карта и собственные
 * формулы расстояния (не {@code VectorSpec}). Совпадение двух алгоритмов на одном ошибочном
 * runtime не доказательство — поэтому сверяемся с этой моделью.
 */
public final class ExactModel<K> {

    private static final double TOLERANCE = 1e-5;

    private final Metric metric;
    private final int dimension;
    private final Map<K, float[]> records = new LinkedHashMap<>();

    public ExactModel(Metric metric, int dimension) {
        this.metric = metric;
        this.dimension = dimension;
    }

    /** Проекция: неверный вектор исключает запись, как и в индексе. */
    public void upsert(K key, float[] vector) {
        if (valid(vector)) {
            records.put(key, vector.clone());
        } else {
            records.remove(key);
        }
    }

    public void delete(K key) {
        records.remove(key);
    }

    public Map<K, float[]> snapshot() {
        Map<K, float[]> copy = new LinkedHashMap<>();
        records.forEach((key, vector) -> copy.put(key, vector.clone()));
        return copy;
    }

    public int size() {
        return records.size();
    }

    public double distance(float[] query, float[] vector) {
        if (metric == Metric.COSINE) {
            double dot = 0;
            double qq = 0;
            double vv = 0;
            for (int i = 0; i < dimension; i++) {
                dot += (double) query[i] * vector[i];
                qq += (double) query[i] * query[i];
                vv += (double) vector[i] * vector[i];
            }
            if (qq == 0 || vv == 0) {
                return 1.0;
            }
            return 1.0 - dot / Math.sqrt(qq * vv);
        }
        double sum = 0;
        for (int i = 0; i < dimension; i++) {
            double d = (double) query[i] - vector[i];
            sum += d * d;
        }
        return sum;
    }

    public List<K> topK(float[] query, int k) {
        List<Map.Entry<K, float[]>> entries = new ArrayList<>(records.entrySet());
        entries.sort(Comparator.comparingDouble(e -> distance(query, e.getValue())));
        List<K> keys = new ArrayList<>();
        for (int i = 0; i < Math.min(k, entries.size()); i++) {
            keys.add(entries.get(i).getKey());
        }
        return keys;
    }

    /** Логическое содержимое вида совпадает с моделью: те же ключи и те же векторы. */
    public void assertSameContent(ReadView<K> view) {
        Map<K, float[]> seen = new HashMap<>();
        view.forEachLive(seen::put);
        assertThat(seen.keySet()).as("live keys").isEqualTo(records.keySet());
        assertThat(view.size()).as("size()").isEqualTo(records.size());
        records.forEach((key, expected) -> {
            float[] actual = seen.get(key);
            if (metric == Metric.COSINE) {
                // Индекс хранит нормализованную копию: сравниваем направления.
                boolean zero = norm(expected) == 0;
                assertThat(norm(actual) == 0).as("zero norm of %s", key).isEqualTo(zero);
                if (!zero) {
                    assertThat(distance(expected, actual)).as("direction of %s", key).isCloseTo(0, within(TOLERANCE));
                }
            } else {
                assertThat(actual).as("vector of %s", key).containsExactly(expected);
            }
        });
    }

    /**
     * Попадания ANN допустимы: живые ключи модели, без повторов, расстояния модели,
     * по возрастанию. Полноту (recall) это не проверяет — она измеряется отдельно.
     */
    public void assertValidHits(float[] query, List<Hit<K>> hits) {
        Set<K> unique = new HashSet<>();
        double previous = Double.NEGATIVE_INFINITY;
        for (Hit<K> hit : hits) {
            assertThat(unique.add(hit.key())).as("duplicate hit %s", hit.key()).isTrue();
            float[] vector = records.get(hit.key());
            assertThat(vector).as("hit %s must be live", hit.key()).isNotNull();
            assertThat(hit.distance()).as("distance of %s", hit.key())
                    .isCloseTo(distance(query, vector), within(TOLERANCE));
            assertThat(hit.distance()).as("ascending order").isGreaterThanOrEqualTo(previous - TOLERANCE);
            previous = hit.distance();
        }
    }

    /** Точный top-k индекса совпадает с моделью (на данных без равных расстояний). */
    public void assertExactTopK(float[] query, int k, List<Hit<K>> hits) {
        assertValidHits(query, hits);
        assertThat(hits).extracting(Hit::key).as("exact top-%d", k).containsExactlyElementsOf(topK(query, k));
    }

    private boolean valid(float[] vector) {
        if (vector == null || vector.length != dimension) {
            return false;
        }
        for (float x : vector) {
            if (!Float.isFinite(x)) {
                return false;
            }
        }
        return true;
    }

    private static double norm(float[] vector) {
        double sum = 0;
        for (float x : vector) {
            sum += (double) x * x;
        }
        return Math.sqrt(sum);
    }
}
