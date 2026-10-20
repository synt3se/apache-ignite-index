package ru.nsu.fit.sberlab.vectorindex.node.index;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.nsu.fit.sberlab.vectorindex.common.ScoredVector;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Shared observable search behavior; small datasets avoid asserting an ANN recall guarantee. */
class PartitionVectorIndexContractTest {
    enum Engine {
        BRUTE_FORCE {
            @Override PartitionVectorIndex create() { return new BruteForcePartitionIndex(); }
        },
        JVECTOR {
            @Override PartitionVectorIndex create() {
                return new JVectorPartitionIndex(3, JVectorTestSupport.properties());
            }
        };

        abstract PartitionVectorIndex create();
    }

    @ParameterizedTest(name = "{0}: empty and nonpositive k")
    @EnumSource(Engine.class)
    void emptyIndexAndNonpositiveKReturnNoNeighbors(Engine engine) throws Exception {
        try (IndexHandle handle = new IndexHandle(engine.create())) {
            PartitionVectorIndex index = handle.index();
            assertThat(index.size()).isZero();
            assertThat(index.search(vector(1, 0, 0), 5)).isEmpty();

            index.add(1, vector(1, 0, 0));
            assertThat(index.search(vector(1, 0, 0), 0)).isEmpty();
            assertThat(index.search(vector(1, 0, 0), -1)).isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}: cosine distances and top-k order")
    @EnumSource(Engine.class)
    void returnsTopKInAscendingCosineDistance(Engine engine) throws Exception {
        try (IndexHandle handle = new IndexHandle(engine.create())) {
            PartitionVectorIndex index = handle.index();
            index.add(1, vector(2, 0, 0));
            index.add(2, vector(1, 1, 0));
            index.add(3, vector(0, 3, 0));
            index.add(4, vector(-1, 0, 0));

            List<ScoredVector> result = index.search(vector(5, 0, 0), 3);

            assertThat(result).extracting(ScoredVector::id).containsExactly(1L, 2L, 3L);
            assertThat(result).isSortedAccordingTo(Comparator.comparingDouble(ScoredVector::distance));
            assertThat(result.get(0).distance()).isCloseTo(0, within(1e-6));
            assertThat(result.get(1).distance()).isCloseTo(1 - 1 / Math.sqrt(2), within(1e-6));
            assertThat(result.get(2).distance()).isCloseTo(1, within(1e-6));
            assertThat(index.search(vector(1, 0, 0), 20)).hasSize(4);
        }
    }

    @ParameterizedTest(name = "{0}: update replaces the previous vector")
    @EnumSource(Engine.class)
    void updateChangesDistanceWithoutDuplicatingId(Engine engine) throws Exception {
        try (IndexHandle handle = new IndexHandle(engine.create())) {
            PartitionVectorIndex index = handle.index();
            index.add(1, vector(1, 0, 0));
            index.add(2, vector(0, 1, 0));
            index.add(1, vector(-1, 0, 0));

            List<ScoredVector> result = index.search(vector(1, 0, 0), 10);
            assertThat(index.size()).isEqualTo(2);
            assertThat(result).extracting(ScoredVector::id).containsExactly(2L, 1L);
            assertThat(result.get(1).distance()).isCloseTo(2, within(1e-6));
        }
    }

    @ParameterizedTest(name = "{0}: delete, reinsert and clear")
    @EnumSource(Engine.class)
    void deletionIsIdempotentAndClearAllowsReuse(Engine engine) throws Exception {
        try (IndexHandle handle = new IndexHandle(engine.create())) {
            PartitionVectorIndex index = handle.index();
            index.add(1, vector(1, 0, 0));
            index.add(2, vector(0, 1, 0));
            index.delete(1);
            index.delete(1);
            index.delete(999);
            assertThat(index.size()).isEqualTo(1);
            assertThat(index.search(vector(1, 0, 0), 10))
                    .extracting(ScoredVector::id).containsExactly(2L);

            index.add(1, vector(-1, 0, 0));
            assertThat(index.size()).isEqualTo(2);
            assertThat(index.search(vector(-1, 0, 0), 1)).extracting(ScoredVector::id).containsExactly(1L);

            index.clear();
            assertThat(index.size()).isZero();
            assertThat(index.search(vector(1, 0, 0), 10)).isEmpty();
            index.add(3, vector(0, 0, 1));
            assertThat(index.size()).isEqualTo(1);
            assertThat(index.search(vector(0, 0, 1), 1)).extracting(ScoredVector::id).containsExactly(3L);
        }
    }

    @ParameterizedTest(name = "{0}: build takes ownership of its data")
    @EnumSource(Engine.class)
    void buildCopiesBothInputMapAndVectors(Engine engine) throws Exception {
        try (IndexHandle handle = new IndexHandle(engine.create())) {
            float[] callerVector = vector(1, 0, 0);
            Map<Long, float[]> source = new HashMap<>(Map.of(1L, callerVector, 2L, vector(0, 1, 0)));
            handle.index().build(source);

            callerVector[0] = -1;
            source.clear();

            assertThat(handle.index().size()).isEqualTo(2);
            List<ScoredVector> result = handle.index().search(vector(1, 0, 0), 2);
            assertThat(result).extracting(ScoredVector::id).containsExactly(1L, 2L);
            assertThat(result.get(0).distance()).isCloseTo(0, within(1e-6));
        }
    }

    @ParameterizedTest(name = "{0}: zero vector has finite distance")
    @EnumSource(Engine.class)
    void zeroNormProducesFiniteDistance(Engine engine) throws Exception {
        try (IndexHandle handle = new IndexHandle(engine.create())) {
            handle.index().add(1, vector(0, 0, 0));
            handle.index().add(2, vector(1, 0, 0));

            assertThat(handle.index().search(vector(0, 0, 0), 10))
                    .allSatisfy(neighbor -> assertThat(neighbor.distance()).isEqualTo(1));
            assertThat(handle.index().search(vector(1, 0, 0), 10))
                    .filteredOn(neighbor -> neighbor.id() == 1)
                    .singleElement().satisfies(neighbor -> assertThat(neighbor.distance()).isEqualTo(1));
        }
    }

    private static float[] vector(float x, float y, float z) {
        return new float[]{x, y, z};
    }

    private record IndexHandle(PartitionVectorIndex index) implements AutoCloseable {
        @Override public void close() throws Exception {
            if (index instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }
}
