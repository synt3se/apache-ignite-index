package ru.nsu.fit.sberlab.vectorindex.node.index;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.nsu.fit.sberlab.vectorindex.common.ScoredVector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertAll;

@Timeout(30)
class JVectorPartitionIndexTest {
    @Test
    void graphMasksDeletedAndSupersededVersions() {
        try (JVectorPartitionIndex index = new JVectorPartitionIndex(3, JVectorTestSupport.properties())) {
            index.build(initialVectors());
            index.add(1, vector(-1, 0, 0));
            index.delete(2);

            List<ScoredVector> result = index.search(vector(1, 0, 0), 10);
            assertThat(index.size()).isEqualTo(2);
            assertThat(result).extracting(ScoredVector::id).containsExactly(3L, 1L);
            assertThat(result.get(1).distance()).isCloseTo(2, within(1e-6));
            assertThat(index.pendingCount()).isEqualTo(1);
        }
    }

    @Test
    void addCopiesCallerOwnedArray() {
        try (JVectorPartitionIndex index = new JVectorPartitionIndex(3, JVectorTestSupport.properties())) {
            float[] source = vector(1, 0, 0);
            index.add(1, source);
            source[0] = -1;

            assertThat(index.search(vector(1, 0, 0), 1)).singleElement()
                    .satisfies(neighbor -> assertThat(neighbor.distance()).isCloseTo(0, within(1e-6)));
        }
    }

    @Test
    void repeatedIdenticalGraphVectorIsSkippedWithoutPendingWork() {
        try (JVectorPartitionIndex index = new JVectorPartitionIndex(3, JVectorTestSupport.properties())) {
            index.build(initialVectors());
            index.add(1, vector(1, 0, 0));

            assertThat(index.dedupSkippedCount()).isEqualTo(1);
            assertThat(index.pendingCount()).isZero();
            assertThat(index.size()).isEqualTo(3);
            assertThat(index.search(vector(1, 0, 0), 1)).extracting(ScoredVector::id).containsExactly(1L);
        }
    }

    @Test
    void rejectsInvalidVectorsBeforeChangingIndex() {
        assertThatIllegalArgumentException().isThrownBy(() -> new JVectorPartitionIndex(0, JVectorTestSupport.properties()));
        try (JVectorPartitionIndex index = new JVectorPartitionIndex(3, JVectorTestSupport.properties())) {
            index.add(1, vector(1, 0, 0));

            assertThatIllegalArgumentException().isThrownBy(() -> index.add(2, null));
            assertThatIllegalArgumentException().isThrownBy(() -> index.add(2, new float[2]));
            assertThatIllegalArgumentException().isThrownBy(() -> index.search(null, 1));
            assertThatIllegalArgumentException().isThrownBy(() -> index.search(new float[2], 1));
            assertThat(index.size()).isEqualTo(1);
        }
    }

    @Test
    void invalidBuildDoesNotLeaveAPartialIndex() {
        try (JVectorPartitionIndex index = new JVectorPartitionIndex(3, JVectorTestSupport.properties())) {
            Map<Long, float[]> input = new HashMap<>();
            input.put(1L, vector(1, 0, 0));
            input.put(2L, new float[2]);
            assertThatIllegalArgumentException().isThrownBy(() -> index.build(input));
            assertThatIllegalArgumentException().isThrownBy(() -> index.build(null));
            input.clear();
            input.put(null, vector(1, 0, 0));
            assertThatIllegalArgumentException().isThrownBy(() -> index.build(input));
            input.clear();
            input.put(1L, null);
            assertThatIllegalArgumentException().isThrownBy(() -> index.seedAndBuildAsync(input));

            assertThat(index.size()).isZero();
            index.build(initialVectors());
            assertThat(index.size()).isEqualTo(3);
        }
    }

    @Test
    void initialBuildCannotSilentlyReplaceExistingData() {
        try (JVectorPartitionIndex index = new JVectorPartitionIndex(3, JVectorTestSupport.properties())) {
            index.build(initialVectors());
            assertThatIllegalStateException().isThrownBy(() -> index.build(Map.of(4L, vector(-1, 0, 0))));
            assertThatIllegalStateException().isThrownBy(() -> index.seedAndBuildAsync(Map.of(4L, vector(-1, 0, 0))));
            assertThat(index.size()).isEqualTo(3);
        }
    }

    @Test
    void seedingMakesAllCopiedVectorsSearchableBeforeGraphPublication() throws Exception {
        try (ControlledIndex fixture = new ControlledIndex()) {
            float[] callerVector = vector(1, 0, 0);
            Map<Long, float[]> source = new HashMap<>(initialVectors());
            source.put(1L, callerVector);
            fixture.properties.pauseNextBuild();
            fixture.index.seedAndBuildAsync(source);
            fixture.properties.awaitCapture();
            callerVector[0] = -1;
            source.clear();

            assertThat(fixture.index.size()).isEqualTo(3);
            assertThat(fixture.index.pendingCount()).isEqualTo(3);
            assertThat(fixture.index.search(vector(1, 0, 0), 3)).extracting(ScoredVector::id)
                    .containsExactlyInAnyOrder(1L, 2L, 3L);
            assertThat(fixture.index.search(vector(1, 0, 0), 1)).singleElement()
                    .satisfies(neighbor -> {
                        assertThat(neighbor.id()).isEqualTo(1L);
                        assertThat(neighbor.distance()).isCloseTo(0, within(1e-6));
                    });

            fixture.finishBuild();
            assertThat(fixture.index.pendingCount()).isZero();
            assertThat(fixture.index.size()).isEqualTo(3);
        }
    }

    @Test
    @Tag("known-issue")
    @DisplayName("К1: delete after merge capture must stay deleted after graph publication")
    void deletionAfterUpdatedVectorWasCapturedMustSurvivePublication() throws Exception {
        try (ControlledIndex fixture = new ControlledIndex()) {
            fixture.index.build(initialVectors());
            fixture.startUpdatingFirstVector();
            fixture.index.delete(1);
            assertThat(fixture.index.size()).as("visible IDs before graph publication").isEqualTo(2);

            fixture.finishBuild();

            // The size invariant detects resurrection even if approximate top-k misses the ID.
            assertAll(
                    () -> assertThat(fixture.index.size()).as("delete must survive graph publication").isEqualTo(2),
                    () -> assertThat(fixture.index.search(vector(-1, 0, 0), 10))
                            .extracting(ScoredVector::id).containsExactlyInAnyOrder(2L, 3L)
            );
        }
    }

    @Test
    void deletionOfUnchangedGraphVectorDuringMergeRemainsDeleted() throws Exception {
        try (ControlledIndex fixture = new ControlledIndex()) {
            fixture.index.build(initialVectors());
            fixture.startUpdatingFirstVector();
            fixture.index.delete(2);
            fixture.finishBuild();

            assertThat(fixture.index.size()).isEqualTo(2);
            assertThat(fixture.index.search(vector(0, 1, 0), 10)).extracting(ScoredVector::id)
                    .containsExactlyInAnyOrder(1L, 3L);
        }
    }

    @Test
    void secondUpdateDuringMergeWinsOverCapturedVersion() throws Exception {
        try (ControlledIndex fixture = new ControlledIndex()) {
            fixture.index.build(initialVectors());
            fixture.startUpdatingFirstVector();
            fixture.index.add(1, vector(0, -1, 0));
            assertFirstVectorDistance(fixture.index, vector(0, -1, 0), 0);

            fixture.finishBuild();

            assertThat(fixture.index.size()).isEqualTo(3);
            assertFirstVectorDistance(fixture.index, vector(0, -1, 0), 0);
            assertFirstVectorDistance(fixture.index, vector(-1, 0, 0), 1);
        }
    }

    @Test
    void deleteThenReinsertDuringMergeKeepsOnlyLatestVector() throws Exception {
        try (ControlledIndex fixture = new ControlledIndex()) {
            fixture.index.build(initialVectors());
            fixture.startUpdatingFirstVector();
            fixture.index.delete(1);
            fixture.index.add(1, vector(0, -1, 0));
            fixture.finishBuild();

            assertThat(fixture.index.size()).isEqualTo(3);
            assertThat(fixture.index.search(vector(0, -1, 0), 10)).extracting(ScoredVector::id)
                    .containsExactlyInAnyOrder(1L, 2L, 3L);
            assertFirstVectorDistance(fixture.index, vector(0, -1, 0), 0);
        }
    }

    @Test
    void clearDuringMergePreventsOldSnapshotFromReturning() throws Exception {
        try (ControlledIndex fixture = new ControlledIndex()) {
            fixture.index.build(initialVectors());
            fixture.startUpdatingFirstVector();
            fixture.index.clear();
            fixture.index.add(4, vector(0, -1, 0));
            fixture.finishBuild();

            assertThat(fixture.index.size()).isEqualTo(1);
            assertThat(fixture.index.search(vector(0, -1, 0), 10)).extracting(ScoredVector::id).containsExactly(4L);
        }
    }

    @Test
    void readersCompleteWhileBackgroundGraphBuildIsPaused() throws Exception {
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try (ControlledIndex fixture = new ControlledIndex()) {
            fixture.index.build(initialVectors());
            fixture.startUpdatingFirstVector();

            Future<List<ScoredVector>> result = reader.submit(() -> fixture.index.search(vector(-1, 0, 0), 3));
            assertThat(result.get(5, TimeUnit.SECONDS)).extracting(ScoredVector::id)
                    .containsExactlyInAnyOrder(1L, 2L, 3L);
        } finally {
            reader.shutdownNow();
        }
    }

    private static void assertFirstVectorDistance(JVectorPartitionIndex index, float[] query, double expected) {
        assertThat(index.search(query, 10)).filteredOn(neighbor -> neighbor.id() == 1)
                .singleElement().satisfies(neighbor -> assertThat(neighbor.distance()).isCloseTo(expected, within(1e-6)));
    }

    private static Map<Long, float[]> initialVectors() {
        return Map.of(1L, vector(1, 0, 0), 2L, vector(0, 1, 0), 3L, vector(0, 0, 1));
    }

    private static float[] vector(float x, float y, float z) { return new float[]{x, y, z}; }

    private static final class ControlledIndex implements AutoCloseable {
        private final JVectorTestSupport.PausingProperties properties = new JVectorTestSupport.PausingProperties();
        private final JVectorPartitionIndex index = new JVectorPartitionIndex(3, properties);

        void startUpdatingFirstVector() throws InterruptedException {
            properties.pauseNextBuild();
            index.add(1, vector(-1, 0, 0));
            properties.awaitCapture();
        }

        void finishBuild() throws ReflectiveOperationException {
            properties.release();
            JVectorTestSupport.awaitRebuildFinished(index);
        }

        @Override public void close() throws Exception {
            try {
                finishBuild();
            } finally {
                index.close();
            }
        }
    }
}
