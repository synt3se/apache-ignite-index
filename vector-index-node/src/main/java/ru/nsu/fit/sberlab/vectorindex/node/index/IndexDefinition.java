package ru.nsu.fit.sberlab.vectorindex.node.index;

import java.util.Objects;

import ru.nsu.fit.sberlab.vectorindex.common.indextype.IndexType;
import ru.nsu.fit.sberlab.vectorindex.core.MaintenancePolicy;
import ru.nsu.fit.sberlab.vectorindex.core.Metric;
import ru.nsu.fit.sberlab.vectorindex.core.VectorSpec;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.VectorAlgorithm;
import ru.nsu.fit.sberlab.vectorindex.core.flat.FlatAlgorithm;
import ru.nsu.fit.sberlab.vectorindex.jvector.JVectorAlgorithm;
import ru.nsu.fit.sberlab.vectorindex.jvector.JVectorBuildParams;

/**
 * Определение индекса для адаптера. В этапе B — одно на узел и выводится из прежней
 * конфигурации сервиса ({@code indexType}, {@code dimension}, {@code jvectorProperties});
 * реестр нескольких определений — этап C.
 */
record IndexDefinition(String indexId, String cacheName, VectorSpec spec, VectorAlgorithm algorithm,
                       MaintenancePolicy policy, IndexType legacyType) {

    IndexDefinition {
        Objects.requireNonNull(indexId, "indexId");
        Objects.requireNonNull(cacheName, "cacheName");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(legacyType, "legacyType");
    }

    /**
     * Прежняя конфигурация: COSINE, JVector или перебор; пороги пересборки из
     * {@link JVectorProperties}; {@code efSearch} остаётся множителем ширины поиска.
     */
    static IndexDefinition fromLegacy(String cacheName, int dimension, IndexType type, JVectorProperties properties) {
        JVectorProperties p = properties == null ? new JVectorProperties() : properties;
        VectorSpec spec = new VectorSpec(dimension, Metric.COSINE);
        MaintenancePolicy policy = MaintenancePolicy.thresholds(p.minAdditionsBeforeRebuild(),
                p.addRebuildRatio(), p.deleteRebuildRatio());
        VectorAlgorithm algorithm = type == IndexType.JVECTOR_INDEX
                ? JVectorAlgorithm.withEfMultiplier(new JVectorBuildParams(p.m(), p.efConstruction(),
                        p.neighborOverflow(), p.alpha(), p.addHierarchy(), p.refineFinalGraph()), p.getEfSearch())
                : FlatAlgorithm.INSTANCE;
        return new IndexDefinition(cacheName, cacheName, spec, algorithm, policy, type);
    }
}
