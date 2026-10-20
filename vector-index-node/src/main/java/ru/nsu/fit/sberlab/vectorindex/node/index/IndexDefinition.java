package ru.nsu.fit.sberlab.vectorindex.node.index;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexInfo;
import ru.nsu.fit.sberlab.vectorindex.common.indextype.IndexType;
import ru.nsu.fit.sberlab.vectorindex.core.MaintenancePolicy;
import ru.nsu.fit.sberlab.vectorindex.core.Metric;
import ru.nsu.fit.sberlab.vectorindex.core.VectorSpec;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.AlgorithmSearchParams;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.VectorAlgorithm;
import ru.nsu.fit.sberlab.vectorindex.core.flat.FlatAlgorithm;
import ru.nsu.fit.sberlab.vectorindex.jvector.JVectorAlgorithm;
import ru.nsu.fit.sberlab.vectorindex.jvector.JVectorBuildParams;
import ru.nsu.fit.sberlab.vectorindex.jvector.JVectorSearchParams;

/**
 * Определение индекса: какие данные индексируются ({@code cacheName}, {@code field}, размерность,
 * метрика) и как строится структура. Статическое и одинаковое на узлах; совместимость между
 * узлами проверяется по {@link #fingerprint()} при каждом поиске.
 *
 * @param field поле значения {@code BinaryObject} с вектором {@code float[]}; {@code null} —
 *              значение кэша само является {@code float[]}
 */
public record IndexDefinition(String indexId, String cacheName, String field, VectorSpec spec,
                              AlgorithmKind kind, VectorAlgorithm algorithm, MaintenancePolicy policy,
                              String fingerprint) {

    public IndexDefinition {
        Objects.requireNonNull(indexId, "indexId");
        Objects.requireNonNull(cacheName, "cacheName");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(fingerprint, "fingerprint");
    }

    /**
     * Определение из настроек. Пороги пересборки — из {@code properties} для обоих алгоритмов;
     * {@code efSearch} остаётся множителем ширины поиска JVector ({@code ef = k × efSearch}),
     * абсолютный {@code ef} задаётся в запросе.
     */
    static IndexDefinition of(String indexId, String cacheName, String field, int dimension, Metric metric,
                              AlgorithmKind kind, JVectorProperties properties) {
        requireText(indexId, "index name");
        requireText(cacheName, "cacheName");
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(kind, "algorithm");
        String vectorField = field == null || field.isBlank() ? null : field.trim();
        JVectorProperties p = properties == null ? new JVectorProperties() : properties;
        VectorSpec spec = new VectorSpec(dimension, metric);
        MaintenancePolicy policy = MaintenancePolicy.thresholds(p.minAdditionsBeforeRebuild(),
                p.addRebuildRatio(), p.deleteRebuildRatio());
        StringBuilder canonical = new StringBuilder("v1|").append(indexId).append('|').append(cacheName)
                .append('|').append(vectorField).append('|').append(dimension).append('|').append(metric)
                .append('|').append(kind);
        VectorAlgorithm algorithm;
        if (kind == AlgorithmKind.JVECTOR) {
            JVectorBuildParams build = new JVectorBuildParams(p.m(), p.efConstruction(), p.neighborOverflow(),
                    p.alpha(), p.addHierarchy(), p.refineFinalGraph());
            algorithm = JVectorAlgorithm.withEfMultiplier(build, p.getEfSearch());
            canonical.append('|').append(build).append("|efSearch=").append(p.getEfSearch());
        } else {
            algorithm = FlatAlgorithm.INSTANCE;
        }
        // Пороги пересборки в отпечаток не входят: они не меняют смысл ответа.
        return new IndexDefinition(indexId, cacheName, vectorField, spec, kind, algorithm, policy,
                sha256(canonical.toString()));
    }

    /** Прежняя конфигурация сервиса: индекс {@code cacheName} по полю {@code vector}, COSINE. */
    static IndexDefinition fromLegacy(String cacheName, int dimension, IndexType type, JVectorProperties properties) {
        return of(cacheName, cacheName, "vector", dimension, Metric.COSINE, AlgorithmKind.fromLegacy(type), properties);
    }

    /**
     * Параметры поиска алгоритма из запроса. Неизвестный параметр или чужой тип значения —
     * {@link IllegalArgumentException}; совместимость с {@code k} (JVector: {@code ef ≥ k})
     * проверяет алгоритм при поиске.
     *
     * @return {@code null} — параметры алгоритма по умолчанию
     */
    public AlgorithmSearchParams searchParams(Map<String, ?> params, int k) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        if (kind == AlgorithmKind.FLAT) {
            throw new IllegalArgumentException("algorithm FLAT has no search parameters: " + params.keySet());
        }
        for (String name : params.keySet()) {
            if (!"ef".equals(name)) {
                throw new IllegalArgumentException("unknown JVECTOR search parameter: " + name);
            }
        }
        Object ef = params.get("ef");
        if (!(ef instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw new IllegalArgumentException("ef must be an integer: " + ef);
        }
        JVectorSearchParams parsed = new JVectorSearchParams(Math.toIntExact(number.longValue()));
        algorithm.validate(parsed, Math.max(k, 1));
        return parsed;
    }

    public IndexInfo info(boolean loaded) {
        IndexInfo info = new IndexInfo();
        info.indexId = indexId;
        info.cacheName = cacheName;
        info.field = field;
        info.dimension = spec.dimension();
        info.metric = spec.metric().name();
        info.algorithm = kind.name();
        info.fingerprint = fingerprint;
        info.loaded = loaded;
        return info;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
