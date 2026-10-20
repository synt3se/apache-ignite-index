package ru.nsu.fit.sberlab.vectorindex.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class VectorSpecTest {
    private final VectorSpec cosine = new VectorSpec(3, Metric.COSINE);
    private final VectorSpec l2 = new VectorSpec(3, Metric.SQUARED_L2);

    @Test
    void cosineIngestReturnsNormalizedCopyThatCallerCannotChange() {
        float[] source = {3, 4, 0};
        float[] accepted = accepted(cosine.ingest(source));
        source[0] = -100;

        assertThat(accepted).isNotSameAs(source);
        assertThat(accepted[0]).isCloseTo(0.6f, within(1e-6f));
        assertThat(accepted[1]).isCloseTo(0.8f, within(1e-6f));
        assertThat(cosine.distance(cosine.prepareQuery(new float[]{6, 8, 0}), accepted)).isCloseTo(0, within(1e-6));
    }

    @Test
    void cosineDistanceMatchesTheClassicalFormula() {
        float[] a = accepted(cosine.ingest(new float[]{1, 1, 0}));
        double distance = cosine.distance(cosine.prepareQuery(new float[]{5, 0, 0}), a);

        assertThat(distance).isCloseTo(1 - 1 / Math.sqrt(2), within(1e-6));
        assertThat(cosine.distance(cosine.prepareQuery(new float[]{-1, 0, 0}),
                accepted(cosine.ingest(new float[]{1, 0, 0})))).isCloseTo(2, within(1e-6));
    }

    @Test
    void zeroNormStaysZeroAndHasDistanceOneToEverything() {
        float[] zero = accepted(cosine.ingest(new float[]{0, 0, 0}));
        float[] x = accepted(cosine.ingest(new float[]{1, 0, 0}));

        assertThat(zero).containsExactly(0, 0, 0);
        assertThat(cosine.distance(cosine.prepareQuery(new float[]{0, 0, 0}), x)).isEqualTo(1.0);
        assertThat(cosine.distance(cosine.prepareQuery(new float[]{1, 0, 0}), zero)).isEqualTo(1.0);
        assertThat(cosine.distance(cosine.prepareQuery(new float[]{0, 0, 0}), zero)).isEqualTo(1.0);
    }

    @Test
    void squaredL2IsNotNormalized() {
        float[] stored = accepted(l2.ingest(new float[]{1, 2, 3}));

        assertThat(stored).containsExactly(1, 2, 3);
        assertThat(l2.distance(l2.prepareQuery(new float[]{1, 2, 3}), stored)).isZero();
        assertThat(l2.distance(l2.prepareQuery(new float[]{0, 0, 0}), stored)).isEqualTo(14.0);
    }

    @Test
    void invalidVectorsAreRejectedWithReasonNotThrown() {
        assertThat(cosine.ingest(new float[2])).isEqualTo(new Ingest.Rejected(RejectReason.WRONG_DIMENSION));
        assertThat(cosine.ingest(new float[]{1, Float.NaN, 0}))
                .isEqualTo(new Ingest.Rejected(RejectReason.NON_FINITE));
        assertThat(l2.ingest(new float[]{Float.POSITIVE_INFINITY, 0, 0}))
                .isEqualTo(new Ingest.Rejected(RejectReason.NON_FINITE));
        assertThat(l2.ingest(new float[]{0, Float.NEGATIVE_INFINITY, 0}))
                .isEqualTo(new Ingest.Rejected(RejectReason.NON_FINITE));
    }

    @Test
    void missingVectorAndInvalidQueriesAreCallerErrors() {
        assertThatIllegalArgumentException().isThrownBy(() -> cosine.ingest(null));
        assertThatIllegalArgumentException().isThrownBy(() -> cosine.prepareQuery(null));
        assertThatIllegalArgumentException().isThrownBy(() -> cosine.prepareQuery(new float[2]));
        assertThatIllegalArgumentException().isThrownBy(() -> l2.prepareQuery(new float[]{0, Float.NaN, 0}));
        assertThatIllegalArgumentException().isThrownBy(() -> new VectorSpec(0, Metric.COSINE));
    }

    @Test
    void preparedQueryIsACopy() {
        float[] query = {1, 2, 3};
        float[] prepared = l2.prepareQuery(query);
        query[0] = 7;

        assertThat(prepared).isNotSameAs(query).containsExactly(1, 2, 3);
    }

    private static float[] accepted(Ingest ingest) {
        assertThat(ingest).isInstanceOf(Ingest.Accepted.class);
        return ((Ingest.Accepted) ingest).vector();
    }
}
