package ru.nsu.fit.sberlab.vectorindex.node.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import ru.nsu.fit.sberlab.vectorindex.common.indextype.IndexType;
import ru.nsu.fit.sberlab.vectorindex.core.Metric;
import ru.nsu.fit.sberlab.vectorindex.jvector.JVectorSearchParams;

class IndexDefinitionTest {

    @Test
    void legacyConfigurationIsTheDemoIndexOverTheVectorField() {
        IndexDefinition legacy = IndexDefinition.fromLegacy("vectors", 512, IndexType.JVECTOR_INDEX, properties());

        assertThat(legacy.indexId()).isEqualTo("vectors");
        assertThat(legacy.cacheName()).isEqualTo("vectors");
        assertThat(legacy.field()).isEqualTo("vector");
        assertThat(legacy.spec().dimension()).isEqualTo(512);
        assertThat(legacy.spec().metric()).isEqualTo(Metric.COSINE);
        assertThat(legacy.kind()).isEqualTo(AlgorithmKind.JVECTOR);
        assertThat(IndexDefinition.fromLegacy("vectors", 512, IndexType.BRUTE_FORCE_INDEX, null).kind())
                .isEqualTo(AlgorithmKind.FLAT);
    }

    @Test
    void invalidSettingsAreRejectedAndBlankFieldMeansTheWholeValue() {
        assertThatIllegalArgumentException().isThrownBy(() -> config(c -> c.setName(" ")).toDefinition());
        assertThatIllegalArgumentException().isThrownBy(() -> config(c -> c.setCacheName(null)).toDefinition());
        assertThatIllegalArgumentException().isThrownBy(() -> config(c -> c.setDimension(0)).toDefinition());
        assertThat(config(c -> c.setField("  ")).toDefinition().field()).isNull();
    }

    @Test
    void fingerprintCoversEverythingThatChangesTheMeaningOfAnAnswer() {
        String base = config(c -> {}).toDefinition().fingerprint();
        assertThat(config(c -> {}).toDefinition().fingerprint()).isEqualTo(base);

        assertThat(config(c -> c.setMetric(Metric.SQUARED_L2)).toDefinition().fingerprint()).isNotEqualTo(base);
        assertThat(config(c -> c.setDimension(64)).toDefinition().fingerprint()).isNotEqualTo(base);
        assertThat(config(c -> c.setField("other")).toDefinition().fingerprint()).isNotEqualTo(base);
        assertThat(config(c -> c.setCacheName("other")).toDefinition().fingerprint()).isNotEqualTo(base);
        assertThat(config(c -> c.setName("other")).toDefinition().fingerprint()).isNotEqualTo(base);
        assertThat(config(c -> c.setAlgorithm(AlgorithmKind.FLAT)).toDefinition().fingerprint()).isNotEqualTo(base);
        assertThat(config(c -> c.getJvectorProperties().setM(8)).toDefinition().fingerprint()).isNotEqualTo(base);
        assertThat(config(c -> c.getJvectorProperties().setEfSearch(4)).toDefinition().fingerprint())
                .isNotEqualTo(base);

        assertThat(config(c -> c.getJvectorProperties().setMinAdditionsBeforeRebuild(1)).toDefinition().fingerprint())
                .as("rebuild thresholds are operational tuning, not the meaning of an answer").isEqualTo(base);
    }

    @Test
    void searchParametersMatchTheAlgorithm() {
        IndexDefinition jvector = config(c -> {}).toDefinition();
        IndexDefinition flat = config(c -> c.setAlgorithm(AlgorithmKind.FLAT)).toDefinition();

        assertThat(jvector.searchParams(Map.of(), 10)).isNull();
        assertThat(jvector.searchParams(Map.of("ef", 40), 10)).isEqualTo(new JVectorSearchParams(40));
        assertThat(jvector.searchParams(Map.of("ef", 40L), 10)).isEqualTo(new JVectorSearchParams(40));
        assertThatIllegalArgumentException().isThrownBy(() -> jvector.searchParams(Map.of("ef", 5), 10));
        assertThatIllegalArgumentException().isThrownBy(() -> jvector.searchParams(Map.of("ef", 40.5), 10));
        assertThatIllegalArgumentException().isThrownBy(() -> jvector.searchParams(Map.of("ef", "40"), 10));
        assertThatIllegalArgumentException().isThrownBy(() -> jvector.searchParams(Map.of("nprobe", 4), 10));
        assertThat(flat.searchParams(null, 10)).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> flat.searchParams(Map.of("ef", 40), 10));
    }

    @Test
    void efOutsideItsRangeIsAnInvalidRequestNotAnArithmeticError() {
        IndexDefinition jvector = config(c -> {}).toDefinition();

        assertThat(jvector.searchParams(Map.of("ef", IndexDefinition.MAX_EF), 10))
                .isEqualTo(new JVectorSearchParams(IndexDefinition.MAX_EF));
        assertThat(jvector.searchParams(Map.of("ef", 10), 10)).as("ef == k").isEqualTo(new JVectorSearchParams(10));
        for (Object outOfRange : new Object[]{2_147_483_648L, Long.MAX_VALUE, Integer.MAX_VALUE,
                IndexDefinition.MAX_EF + 1, 0, -1, 1e10, Double.NaN, Double.POSITIVE_INFINITY,
                new java.math.BigInteger("99999999999999999999")}) {
            assertThatIllegalArgumentException().as("ef = " + outOfRange)
                    .isThrownBy(() -> jvector.searchParams(Map.of("ef", outOfRange), 10));
        }
    }

    @Test
    void configurationSurvivesSerializationWithNestedSettings() throws Exception {
        VectorIndexConfiguration original = config(c -> {});
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        VectorIndexConfiguration copy;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (VectorIndexConfiguration) in.readObject();
        }

        assertThat(copy.getJvectorProperties().m()).isEqualTo(16);
        assertThat(copy.toDefinition().fingerprint()).isEqualTo(original.toDefinition().fingerprint());
    }

    private static VectorIndexConfiguration config(Consumer<VectorIndexConfiguration> tweak) {
        VectorIndexConfiguration c = new VectorIndexConfiguration();
        c.setName("images");
        c.setCacheName("vectors");
        c.setField("vector");
        c.setDimension(128);
        c.setMetric(Metric.COSINE);
        c.setAlgorithm(AlgorithmKind.JVECTOR);
        c.setJvectorProperties(properties());
        tweak.accept(c);
        return c;
    }

    static JVectorProperties properties() {
        JVectorProperties p = new JVectorProperties();
        p.setM(16);
        p.setEfConstruction(64);
        p.setEfSearch(2);
        p.setNeighborOverflow(1.2f);
        p.setAlpha(1.2f);
        p.setAddHierarchy(false);
        p.setRefineFinalGraph(true);
        p.setAddRebuildRatio(0.1);
        p.setDeleteRebuildRatio(0.2);
        p.setMinAdditionsBeforeRebuild(256);
        return p;
    }
}
