package ru.nsu.fit.vector.node.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;
import ru.nsu.fit.vector.common.ScoredVector;

class BruteForcePartitionIndexTest {

    private static float[] oneHot(int i) {
        float[] v = new float[16];
        v[i] = 1f;
        return v;
    }

    @Test
    void exactMatchIsFirstWithZeroDistanceAndSorted() {
        BruteForcePartitionIndex idx = new BruteForcePartitionIndex();
        for (int i = 0; i < 5; i++) idx.add(i, oneHot(i));

        List<ScoredVector> r = idx.search(oneHot(2), 3);

        assertThat(r).hasSize(3);
        assertThat(r.get(0).id()).isEqualTo(2L);
        assertThat(r.get(0).distance()).isCloseTo(0.0, within(1e-6));
        assertThat(r).isSortedAccordingTo(Comparator.comparingDouble(ScoredVector::distance));
    }

    @Test
    void deletedVectorDisappearsFromResults() {
        BruteForcePartitionIndex idx = new BruteForcePartitionIndex();
        idx.add(1, oneHot(1));
        idx.add(2, oneHot(2));

        idx.delete(1);

        assertThat(idx.search(oneHot(1), 5))
                .extracting(ScoredVector::id)
                .doesNotContain(1L);
    }
}