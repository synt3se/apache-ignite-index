package ru.nsu.fit.sberlab.vectorindex.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ru.nsu.fit.sberlab.vectorindex.core.ApplyResult;
import ru.nsu.fit.sberlab.vectorindex.core.BuildToken;
import ru.nsu.fit.sberlab.vectorindex.core.Hit;
import ru.nsu.fit.sberlab.vectorindex.core.Metric;
import ru.nsu.fit.sberlab.vectorindex.core.OptimizationState;
import ru.nsu.fit.sberlab.vectorindex.core.Phase;
import ru.nsu.fit.sberlab.vectorindex.core.PublishResult;
import ru.nsu.fit.sberlab.vectorindex.core.ReadView;
import ru.nsu.fit.sberlab.vectorindex.core.RejectReason;
import ru.nsu.fit.sberlab.vectorindex.core.RepairReason;
import ru.nsu.fit.sberlab.vectorindex.core.ScanSession;
import ru.nsu.fit.sberlab.vectorindex.core.SearchParams;
import ru.nsu.fit.sberlab.vectorindex.core.VectorSpec;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.VectorAlgorithm;

/**
 * Один контрактный набор для всех алгоритмов: логическое содержимое и значения сверяются
 * с независимой {@link ExactModel}; на малых наборах ANN видит все кандидаты, поэтому
 * сценарии старых тестов проверяют и выдачу. ANN recall на больших наборах — отдельно.
 * Сюда перенесены сценарии {@code PartitionVectorIndexContractTest},
 * {@code BruteForcePartitionIndexTest}, {@code JVectorPartitionIndexTest} и расписания §7.10.
 */
@Timeout(60)
public abstract class PartitionRuntimeContract {

    private static final VectorSpec COSINE3 = new VectorSpec(3, Metric.COSINE);

    /** Алгоритм под проверкой; новый экземпляр на каждый вызов. */
    protected abstract VectorAlgorithm algorithm();

    protected RuntimeHarness harness(VectorSpec spec) {
        return new RuntimeHarness(spec, algorithm());
    }

    // ------------------------------------------------------- общий контракт поиска

    @Test
    void emptyIndexAndNonpositiveKReturnNoNeighbors() {
        try (RuntimeHarness h = harness(COSINE3).ready()) {
            assertThat(h.size()).isZero();
            assertThat(h.search(v(1, 0, 0), 5)).isEmpty();

            h.runtime.upsert(1L, v(1, 0, 0));
            assertThat(h.search(v(1, 0, 0), 0)).isEmpty();
            assertThat(h.search(v(1, 0, 0), -1)).isEmpty();
        }
    }

    @Test
    void returnsTopKInAscendingCosineDistance() throws Exception {
        try (RuntimeHarness h = harness(COSINE3).ready()) {
            h.runtime.upsert(1L, v(2, 0, 0));
            h.runtime.upsert(2L, v(1, 1, 0));
            h.runtime.upsert(3L, v(0, 3, 0));
            h.runtime.upsert(4L, v(-1, 0, 0));
            assertTopKInAscendingCosineDistance(h);
            h.optimize();
            assertTopKInAscendingCosineDistance(h);
        }
    }

    private static void assertTopKInAscendingCosineDistance(RuntimeHarness h) {
        List<Hit<Long>> result = h.search(v(5, 0, 0), 3);

        assertThat(result).extracting(Hit::key).containsExactly(1L, 2L, 3L);
        assertThat(result).isSortedAccordingTo(Comparator.comparingDouble(Hit::distance));
        assertThat(result.get(0).distance()).isCloseTo(0, within(1e-6));
        assertThat(result.get(1).distance()).isCloseTo(1 - 1 / Math.sqrt(2), within(1e-6));
        assertThat(result.get(2).distance()).isCloseTo(1, within(1e-6));
        assertThat(h.search(v(1, 0, 0), 20)).hasSize(4);
    }

    @Test
    void updateChangesDistanceWithoutDuplicatingId() throws Exception {
        try (RuntimeHarness h = harness(COSINE3).ready()) {
            h.runtime.upsert(1L, v(1, 0, 0));
            h.runtime.upsert(2L, v(0, 1, 0));
            h.optimize();
            h.runtime.upsert(1L, v(-1, 0, 0));

            List<Hit<Long>> result = h.search(v(1, 0, 0), 10);
            assertThat(h.size()).isEqualTo(2);
            assertThat(result).extracting(Hit::key).containsExactly(2L, 1L);
            assertThat(result.get(1).distance()).isCloseTo(2, within(1e-6));
        }
    }

    @Test
    void deletionIsIdempotentAndClearAllowsReuse() throws Exception {
        RuntimeHarness h = harness(COSINE3).ready();
        h.runtime.upsert(1L, v(1, 0, 0));
        h.runtime.upsert(2L, v(0, 1, 0));
        h.optimize();
        assertThat(h.runtime.delete(1L).status()).isEqualTo(ApplyResult.Status.APPLIED);
        assertThat(h.runtime.delete(1L).status()).isEqualTo(ApplyResult.Status.UNCHANGED);
        assertThat(h.runtime.delete(999L).status()).isEqualTo(ApplyResult.Status.UNCHANGED);
        assertThat(h.size()).isEqualTo(1);
        assertThat(h.search(v(1, 0, 0), 10)).extracting(Hit::key).containsExactly(2L);

        h.runtime.upsert(1L, v(-1, 0, 0));
        assertThat(h.size()).isEqualTo(2);
        assertThat(h.search(v(-1, 0, 0), 1)).extracting(Hit::key).containsExactly(1L);

        // В ядре нет clear: очистка — завершение эпизода и новый runtime.
        h.close();
        assertThat(h.runtime.upsert(3L, v(0, 0, 1)).status()).isEqualTo(ApplyResult.Status.CLOSED);
        try (RuntimeHarness fresh = harness(COSINE3).ready()) {
            assertThat(fresh.size()).isZero();
            assertThat(fresh.search(v(1, 0, 0), 10)).isEmpty();
            fresh.runtime.upsert(3L, v(0, 0, 1));
            assertThat(fresh.size()).isEqualTo(1);
            assertThat(fresh.search(v(0, 0, 1), 1)).extracting(Hit::key).containsExactly(3L);
        }
    }

    @Test
    @DisplayName("scan takes ownership of the caller's records")
    void scanCopiesCallerArrays() throws Exception {
        try (RuntimeHarness h = harness(COSINE3)) {
            float[] callerVector = v(1, 0, 0);
            Map<Long, float[]> source = new HashMap<>(Map.of(1L, callerVector, 2L, v(0, 1, 0)));
            ScanSession<Long> scan = h.runtime.beginScan();
            source.forEach(scan::offer);
            callerVector[0] = -1;
            source.clear();
            h.runtime.publishScan(scan);
            h.optimize();

            assertThat(h.size()).isEqualTo(2);
            List<Hit<Long>> result = h.search(v(1, 0, 0), 2);
            assertThat(result).extracting(Hit::key).containsExactly(1L, 2L);
            assertThat(result.get(0).distance()).isCloseTo(0, within(1e-6));
        }
    }

    @Test
    void zeroNormProducesFiniteDistance() throws Exception {
        try (RuntimeHarness h = harness(COSINE3).ready()) {
            h.runtime.upsert(1L, v(0, 0, 0));
            h.runtime.upsert(2L, v(1, 0, 0));
            assertZeroNormDistances(h);
            h.optimize();
            assertZeroNormDistances(h);
        }
    }

    private static void assertZeroNormDistances(RuntimeHarness h) {
        assertThat(h.search(v(0, 0, 0), 10))
                .allSatisfy(neighbor -> assertThat(neighbor.distance()).isEqualTo(1));
        assertThat(h.search(v(1, 0, 0), 10))
                .filteredOn(neighbor -> neighbor.key() == 1L)
                .singleElement().satisfies(neighbor -> assertThat(neighbor.distance()).isEqualTo(1));
    }

    @Test
    void exactMatchIsFirstWithZeroDistanceAndSorted() throws Exception {
        try (RuntimeHarness h = harness(new VectorSpec(16, Metric.COSINE)).ready()) {
            for (int i = 0; i < 5; i++) h.runtime.upsert((long) i, oneHot(i));
            h.optimize();

            List<Hit<Long>> r = h.search(oneHot(2), 3);

            assertThat(r).hasSize(3);
            assertThat(r.get(0).key()).isEqualTo(2L);
            assertThat(r.get(0).distance()).isCloseTo(0.0, within(1e-6));
            assertThat(r).isSortedAccordingTo(Comparator.comparingDouble(Hit::distance));
        }
    }

    @Test
    void deletedVectorDisappearsFromResults() throws Exception {
        try (RuntimeHarness h = harness(new VectorSpec(16, Metric.COSINE)).ready()) {
            h.runtime.upsert(1L, oneHot(1));
            h.runtime.upsert(2L, oneHot(2));
            h.optimize();
            h.runtime.delete(1L);

            assertThat(h.search(oneHot(1), 5)).extracting(Hit::key).doesNotContain(1L);
        }
    }

    @Test
    @DisplayName("Input ownership: mutating upsert's float[] must not change the indexed vector")
    void addCopiesCallerOwnedArray() throws Exception {
        try (RuntimeHarness h = harness(new VectorSpec(16, Metric.COSINE)).ready()) {
            float[] callerVector = oneHot(1);
            h.runtime.upsert(1L, callerVector);
            callerVector[1] = -1;
            assertThat(h.search(oneHot(1), 1)).singleElement()
                    .satisfies(neighbor -> assertThat(neighbor.distance()).isCloseTo(0, within(1e-6)));
            h.optimize();
            assertThat(h.search(oneHot(1), 1)).singleElement()
                    .satisfies(neighbor -> assertThat(neighbor.distance()).isCloseTo(0, within(1e-6)));
        }
    }

    @Test
    void squaredL2UsesTheSameDistanceInBaseAndDelta() throws Exception {
        try (RuntimeHarness h = harness(new VectorSpec(2, Metric.SQUARED_L2)).built(Map.of(
                1L, new float[]{1, 0}, 2L, new float[]{0, 3}))) {
            h.runtime.upsert(3L, new float[]{2, 2});

            assertThat(h.search(new float[]{0, 0}, 3)).extracting(Hit::key, Hit::distance).containsExactly(
                    org.assertj.core.groups.Tuple.tuple(1L, 1.0),
                    org.assertj.core.groups.Tuple.tuple(3L, 8.0),
                    org.assertj.core.groups.Tuple.tuple(2L, 9.0));
        }
    }

    // ------------------------------------------------------- база и дельта

    @Test
    void baseMasksDeletedAndSupersededVersions() throws Exception {
        try (RuntimeHarness h = harness(COSINE3).built(initialVectors())) {
            h.runtime.upsert(1L, v(-1, 0, 0));
            h.runtime.delete(2L);

            List<Hit<Long>> result = h.search(v(1, 0, 0), 10);
            assertThat(h.size()).isEqualTo(2);
            assertThat(result).extracting(Hit::key).containsExactly(3L, 1L);
            assertThat(result.get(1).distance()).isCloseTo(2, within(1e-6));
            assertThat(h.status().deltaLive()).isEqualTo(1);
            assertThat(h.status().deltaTombstones()).isEqualTo(1);
        }
    }

    @Test
    void repeatedIdenticalVectorIsSkippedWithoutDeltaWork() throws Exception {
        try (RuntimeHarness h = harness(COSINE3).built(initialVectors())) {
            assertThat(h.runtime.upsert(1L, v(1, 0, 0)).status()).isEqualTo(ApplyResult.Status.UNCHANGED);
            assertThat(h.runtime.upsert(1L, v(2, 0, 0)).status())
                    .as("same direction normalizes to the same accepted vector").isEqualTo(ApplyResult.Status.UNCHANGED);

            assertThat(h.status().dedupSkipped()).isEqualTo(2);
            assertThat(h.status().deltaEntries()).isZero();
            assertThat(h.size()).isEqualTo(3);
            assertThat(h.search(v(1, 0, 0), 1)).extracting(Hit::key).containsExactly(1L);
        }
    }

    @Test
    void rejectsInvalidVectorsBeforeChangingIndex() {
        assertThatIllegalArgumentException().isThrownBy(() -> new VectorSpec(0, Metric.COSINE));
        try (RuntimeHarness h = harness(COSINE3).ready()) {
            h.runtime.upsert(1L, v(1, 0, 0));

            assertThatIllegalArgumentException().isThrownBy(() -> h.runtime.upsert(2L, null));
            assertThat(h.runtime.upsert(2L, new float[2]).reason()).isEqualTo(RejectReason.WRONG_DIMENSION);
            assertThat(h.runtime.upsert(2L, v(Float.NaN, 0, 0)).reason()).isEqualTo(RejectReason.NON_FINITE);
            assertThat(h.runtime.upsert(2L, v(0, Float.POSITIVE_INFINITY, 0)).reason())
                    .isEqualTo(RejectReason.NON_FINITE);
            try (ReadView<Long> view = h.view()) {
                assertThatIllegalArgumentException().isThrownBy(() -> view.search(null, SearchParams.topK(1)));
                assertThatIllegalArgumentException().isThrownBy(() -> view.search(new float[2], SearchParams.topK(1)));
            }
            assertThat(h.size()).isEqualTo(1);
            assertThat(h.status().rejectedTotal()).isEqualTo(3);
        }
    }

    @Test
    void invalidUpdateRemovesPreviousVectorFromProjection() throws Exception {
        try (RuntimeHarness h = harness(COSINE3).built(initialVectors())) {
            ApplyResult result = h.runtime.upsert(1L, v(Float.NaN, 0, 0));

            assertThat(result.status()).isEqualTo(ApplyResult.Status.REJECTED);
            assertThat(h.content()).containsOnlyKeys(2L, 3L);
            assertThat(h.search(v(1, 0, 0), 10)).extracting(Hit::key).containsExactlyInAnyOrder(2L, 3L);
            assertThat(h.status().rejectedVectors()).containsEntry(RejectReason.NON_FINITE, 1L);
        }
    }

    @Test
    void scanRejectsInvalidRecordsIndividually() throws Exception {
        try (RuntimeHarness h = harness(COSINE3)) {
            ScanSession<Long> scan = h.runtime.beginScan();
            assertThat(scan.offer(1L, v(1, 0, 0))).isEqualTo(ApplyResult.Status.APPLIED);
            assertThat(scan.offer(2L, new float[2])).isEqualTo(ApplyResult.Status.REJECTED);
            assertThat(scan.offer(3L, v(0, Float.NaN, 0))).isEqualTo(ApplyResult.Status.REJECTED);
            assertThatIllegalArgumentException().isThrownBy(() -> scan.offer(4L, null));
            assertThat(h.runtime.publishScan(scan)).isEqualTo(PublishResult.PUBLISHED);
            h.optimize();

            assertThat(h.content()).containsOnlyKeys(1L);
            assertThat(h.status().rejectedTotal()).isEqualTo(2);
        }
    }

    @Test
    void staleOrConcurrentPublicationIsRejected() throws Exception {
        try (RuntimeHarness h = harness(COSINE3).built(initialVectors())) {
            h.runtime.upsert(4L, v(-1, 0, 0));
            BuildToken<Long> token = h.capture();
            assertThat(h.runtime.capture()).as("one build per runtime").isEmpty();

            ScanSession<Long> scan = h.runtime.beginScan();
            initialVectors().forEach(scan::offer);
            assertThat(h.runtime.publishScan(scan)).isEqualTo(PublishResult.PUBLISHED);
            assertThat(h.finish(token)).isEqualTo(PublishResult.REJECTED_STALE);
            assertThat(h.content()).containsOnlyKeys(1L, 2L, 3L);
            assertThat(h.status().publishRejected()).isEqualTo(1);

            BuildToken<Long> next = h.capture();
            h.runtime.close();
            assertThat(h.finish(next)).isEqualTo(PublishResult.REJECTED_STALE);
        }
    }

    @Test
    void scanPublishesExactBaseBeforeOptimization() throws Exception {
        try (RuntimeHarness h = harness(COSINE3)) {
            float[] callerVector = v(1, 0, 0);
            ScanSession<Long> scan = h.runtime.beginScan();
            scan.offer(1L, callerVector);
            scan.offer(2L, v(0, 1, 0));
            scan.offer(3L, v(0, 0, 1));
            callerVector[0] = -1;
            h.runtime.publishScan(scan);

            assertThat(h.status().phase()).isEqualTo(Phase.READY);
            assertThat(h.status().optimization()).isEqualTo(OptimizationState.NOT_BUILT);
            assertThat(h.size()).isEqualTo(3);
            assertThat(h.search(v(1, 0, 0), 3)).extracting(Hit::key).containsExactlyInAnyOrder(1L, 2L, 3L);
            assertThat(h.search(v(1, 0, 0), 1)).singleElement().satisfies(neighbor -> {
                assertThat(neighbor.key()).isEqualTo(1L);
                assertThat(neighbor.distance()).isCloseTo(0, within(1e-6));
            });

            h.optimize();
            assertThat(h.status().optimization()).isEqualTo(OptimizationState.BUILT);
            assertThat(h.status().deltaEntries()).isZero();
            assertThat(h.size()).isEqualTo(3);
        }
    }

    // ------------------------------------------------------- расписания сборки (§7.10)

    @Test
    @DisplayName("К1: delete after merge capture must stay deleted after graph publication")
    void deletionAfterUpdatedVectorWasCapturedMustSurvivePublication() throws Exception {
        try (RuntimeHarness h = harness(COSINE3).built(initialVectors())) {
            BuildToken<Long> token = startUpdatingFirstVector(h);
            h.runtime.delete(1L);
            assertThat(h.size()).as("visible IDs before graph publication").isEqualTo(2);

            assertThat(h.finish(token)).isEqualTo(PublishResult.PUBLISHED);

            // Размер ловит воскрешение, даже если приближённый top-k промахнулся бы мимо ID.
            assertAll(
                    () -> assertThat(h.size()).as("delete must survive graph publication").isEqualTo(2),
                    () -> assertThat(h.search(v(-1, 0, 0), 10))
                            .extracting(Hit::key).containsExactlyInAnyOrder(2L, 3L)
            );
        }
    }

    @Test
    void deletionOfUnchangedGraphVectorDuringMergeRemainsDeleted() throws Exception {
        try (RuntimeHarness h = harness(COSINE3).built(initialVectors())) {
            BuildToken<Long> token = startUpdatingFirstVector(h);
            h.runtime.delete(2L);
            h.finish(token);

            assertThat(h.size()).isEqualTo(2);
            assertThat(h.search(v(0, 1, 0), 10)).extracting(Hit::key).containsExactlyInAnyOrder(1L, 3L);
        }
    }

    @Test
    void secondUpdateDuringMergeWinsOverCapturedVersion() throws Exception {
        try (RuntimeHarness h = harness(COSINE3).built(initialVectors())) {
            BuildToken<Long> token = startUpdatingFirstVector(h);
            h.runtime.upsert(1L, v(0, -1, 0));
            assertFirstVectorDistance(h, v(0, -1, 0), 0);

            h.finish(token);

            assertThat(h.size()).isEqualTo(3);
            assertFirstVectorDistance(h, v(0, -1, 0), 0);
            assertFirstVectorDistance(h, v(-1, 0, 0), 1);
        }
    }

    @Test
    void deleteThenReinsertDuringMergeKeepsOnlyLatestVector() throws Exception {
        try (RuntimeHarness h = harness(COSINE3).built(initialVectors())) {
            BuildToken<Long> token = startUpdatingFirstVector(h);
            h.runtime.delete(1L);
            h.runtime.upsert(1L, v(0, -1, 0));
            h.finish(token);

            assertThat(h.size()).isEqualTo(3);
            assertThat(h.search(v(0, -1, 0), 10)).extracting(Hit::key).containsExactlyInAnyOrder(1L, 2L, 3L);
            assertFirstVectorDistance(h, v(0, -1, 0), 0);
        }
    }

    @Test
    void captureDeleteThenReinsertThenPublish() throws Exception {
        try (RuntimeHarness h = harness(COSINE3).built(initialVectors())) {
            h.runtime.delete(1L);
            BuildToken<Long> token = h.capture();
            h.runtime.upsert(1L, v(0, 0, -1));
            h.finish(token);

            assertThat(h.size()).isEqualTo(3);
            assertFirstVectorDistance(h, v(0, 0, -1), 0);
            assertThat(h.status().deltaEntries()).isEqualTo(1);
        }
    }

    @Test
    void lateBuildAfterCloseIsNotPublished() throws Exception {
        RuntimeHarness old = harness(COSINE3).built(initialVectors());
        BuildToken<Long> token = startUpdatingFirstVector(old);
        old.close();                                       // revoke / drop: эпизод завершён
        try (RuntimeHarness replacement = harness(COSINE3).ready()) {
            replacement.runtime.upsert(4L, v(0, -1, 0));

            assertThat(old.finish(token)).isEqualTo(PublishResult.REJECTED_STALE);
            assertThat(old.runtime.upsert(5L, v(1, 1, 1)).status()).isEqualTo(ApplyResult.Status.CLOSED);
            assertThat(old.runtime.openView()).isEmpty();
            assertThat(old.algorithm.openStructures()).as("nothing of the old episode stays open").isZero();
            assertThat(replacement.size()).isEqualTo(1);
            assertThat(replacement.search(v(0, -1, 0), 10)).extracting(Hit::key).containsExactly(4L);
        }
    }

    @Test
    void lateBuildAfterDefinitionChangeIsNotPublished() throws Exception {
        RuntimeHarness old = harness(COSINE3).built(initialVectors());
        BuildToken<Long> token = startUpdatingFirstVector(old);
        old.close();
        try (RuntimeHarness redefined = harness(new VectorSpec(2, Metric.SQUARED_L2)).ready()) {
            redefined.runtime.upsert(9L, new float[]{1, 1});
            assertThat(old.finish(token)).isEqualTo(PublishResult.REJECTED_STALE);
            assertThat(redefined.content()).containsOnlyKeys(9L);
        }
    }

    @Test
    void heldViewDelaysReleaseOfReplacedBaseUntilClosed() throws Exception {
        try (RuntimeHarness h = harness(COSINE3).built(initialVectors())) {
            TrackingAlgorithm.Tracked first = h.algorithm.last();
            ReadView<Long> held = h.view();
            h.runtime.upsert(4L, v(-1, 0, 0));
            h.optimize();

            assertThat(first.closed()).isFalse();
            assertThat(held.search(v(1, 0, 0), SearchParams.topK(10)).hits())
                    .extracting(Hit::key).containsExactlyInAnyOrder(1L, 2L, 3L);
            held.close();
            held.close();
            assertThat(first.closed()).isTrue();
            assertThat(h.algorithm.openStructures()).isEqualTo(1);
        }
    }

    @Test
    void failedBuildKeepsPreviousViewAndReportsStatus() throws Exception {
        try (RuntimeHarness h = harness(COSINE3).built(initialVectors())) {
            h.runtime.upsert(4L, v(-1, 0, 0));
            BuildToken<Long> token = h.capture();
            h.algorithm.failNextBuilds(1);
            try {
                h.build(token);
                throw new AssertionError("build should have failed");
            } catch (IllegalStateException expected) {
                h.runtime.buildFailed(token, expected);
            }

            assertThat(h.status().optimization()).isEqualTo(OptimizationState.FAILED);
            assertThat(h.status().lastBuildFailure()).contains("simulated build failure");
            assertThat(h.status().frozenLayers()).isEqualTo(1);
            assertThat(h.search(v(-1, 0, 0), 1)).extracting(Hit::key).containsExactly(4L);
            assertThat(h.size()).isEqualTo(4);

            h.optimize();
            assertThat(h.status().optimization()).isEqualTo(OptimizationState.BUILT);
            assertThat(h.status().lastBuildFailure()).isNull();
            assertThat(h.size()).isEqualTo(4);
        }
    }

    @Test
    void failedScanKeepsPreviousViewAndPendingRepair() throws Exception {
        try (RuntimeHarness h = harness(COSINE3).built(initialVectors())) {
            h.runtime.requestRepair(RepairReason.APPLIER_OVERFLOW);
            ScanSession<Long> scan = h.runtime.beginScan();
            scan.offer(1L, v(0, 0, -1));
            scan.close(); // скан упал: abort

            assertThat(h.status().phase()).isEqualTo(Phase.READY);
            assertThat(h.status().repairPending()).isTrue();
            assertThat(h.status().knownGap()).isTrue();
            assertThat(h.content()).containsOnlyKeys(1L, 2L, 3L);
            assertFirstVectorDistance(h, v(1, 0, 0), 0);
        }
    }

    @Test
    void readersCompleteWhileBackgroundBuildIsPaused() throws Exception {
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try (RuntimeHarness h = harness(COSINE3).built(initialVectors())) {
            BuildToken<Long> token = startUpdatingFirstVector(h);

            Future<List<Hit<Long>>> result = reader.submit(() -> h.search(v(-1, 0, 0), 3));
            assertThat(result.get(5, TimeUnit.SECONDS)).extracting(Hit::key)
                    .containsExactlyInAnyOrder(1L, 2L, 3L);
            h.finish(token);
        } finally {
            reader.shutdownNow();
        }
    }

    @Test
    void writerDoesNotWaitForOpenView() throws Exception {
        ExecutorService writer = Executors.newSingleThreadExecutor();
        try (RuntimeHarness h = harness(COSINE3).built(initialVectors()); ReadView<Long> open = h.view()) {
            Future<ApplyResult> write = writer.submit(() -> h.runtime.upsert(4L, v(-1, 0, 0)));
            assertThat(write.get(5, TimeUnit.SECONDS).status()).isEqualTo(ApplyResult.Status.APPLIED);
            assertThat(open.size()).as("open view keeps its snapshot").isEqualTo(3);
            assertThat(h.size()).isEqualTo(4);
        } finally {
            writer.shutdownNow();
        }
    }

    // ------------------------------------------------------- скан и сверка (§7.10)

    @Test
    void changeAfterScanCursorPassedTheKeyWins() throws Exception {
        try (RuntimeHarness h = harness(COSINE3).built(initialVectors())) {
            ScanSession<Long> scan = h.runtime.beginScan();
            scan.offer(1L, v(1, 0, 0));
            h.runtime.upsert(1L, v(0, 0, -1));   // курсор уже прошёл ключ
            scan.offer(2L, v(0, 1, 0));
            scan.offer(3L, v(0, 0, 1));
            h.runtime.publishScan(scan);
            h.optimize();

            assertFirstVectorDistance(h, v(0, 0, -1), 0);
        }
    }

    @Test
    void changeLostAfterCursorPassedIsRepairedByTheNextScan() throws Exception {
        try (RuntimeHarness h = harness(COSINE3).built(initialVectors())) {
            ScanSession<Long> scan = h.runtime.beginScan();
            initialVectors().forEach(scan::offer);
            // Источник изменил ключ 1, но событие потеряно переполнением очереди.
            h.runtime.requestRepair(RepairReason.APPLIER_OVERFLOW);
            h.runtime.publishScan(scan);
            assertThat(h.status().repairPending()).as("overflow during the running scan survives it").isTrue();

            Map<Long, float[]> source = new HashMap<>(initialVectors());
            source.put(1L, v(0, 0, -1));
            h.ready(source);

            assertThat(h.status().repairPending()).isFalse();
            assertFirstVectorDistance(h, v(0, 0, -1), 0);
        }
    }

    @Test
    void repeatedChangesConverge() throws Exception {
        try (RuntimeHarness h = harness(COSINE3).built(initialVectors())) {
            for (int i = 0; i < 3; i++) {
                h.runtime.upsert(1L, v(0, 0, -1));
                h.runtime.delete(2L);
            }
            assertThat(h.status().deltaEntries()).isEqualTo(2);
            h.optimize();
            assertThat(h.content()).containsOnlyKeys(1L, 3L);
            assertFirstVectorDistance(h, v(0, 0, -1), 0);
        }
    }

    @Test
    void twoRuntimesAreIndependent() throws Exception {
        try (RuntimeHarness a = harness(COSINE3).built(initialVectors());
             RuntimeHarness b = harness(new VectorSpec(2, Metric.SQUARED_L2)).built(Map.of(1L, new float[]{5, 5}))) {
            a.runtime.upsert(4L, v(-1, 0, 0));
            BuildToken<Long> failing = a.capture();
            a.algorithm.failNextBuilds(1);
            try {
                a.build(failing);
            } catch (IllegalStateException expected) {
                a.runtime.buildFailed(failing, expected);
            }
            a.close();

            assertThat(b.status().optimization()).isEqualTo(OptimizationState.BUILT);
            assertThat(b.status().buildFailures()).isZero();
            assertThat(b.search(new float[]{5, 5}, 1)).extracting(Hit::key).containsExactly(1L);
        }
    }

    // ------------------------------------------------------- модель и неизменяемость

    @Test
    void randomizedSchedulesMatchTheExactModel() throws Exception {
        VectorSpec spec = new VectorSpec(8, Metric.COSINE);
        for (long seed = 1; seed <= 5; seed++) {
            Random random = new Random(seed);
            ExactModel<Long> source = new ExactModel<>(spec.metric(), spec.dimension());
            try (RuntimeHarness h = harness(spec).ready()) {
                BuildToken<Long> building = null;
                ScanSession<Long> scan = null;
                List<Long> scanOrder = null;
                int scanCursor = 0;
                boolean lost = false;
                for (int step = 0; step < 400; step++) {
                    int op = random.nextInt(100);
                    long key = random.nextInt(40);
                    if (op < 40) {                                   // upsert, иногда неверный вектор
                        float[] vector = random.nextInt(25) == 0 ? new float[]{Float.NaN, 0, 0, 0, 0, 0, 0, 0}
                                : gaussian(random, spec.dimension());
                        source.upsert(key, vector);
                        if (random.nextInt(30) == 0) {               // событие потеряно
                            h.runtime.requestRepair(RepairReason.APPLIER_OVERFLOW);
                            lost = true;
                        } else {
                            h.runtime.upsert(key, vector);
                        }
                    } else if (op < 55) {
                        source.delete(key);
                        h.runtime.delete(key);
                    } else if (op < 65 && building == null && scan == null) {
                        building = h.runtime.capture().orElse(null);
                    } else if (op < 75 && building != null) {
                        if (random.nextInt(5) == 0) {
                            h.algorithm.failNextBuilds(1);
                        }
                        try {
                            h.runtime.publish(building, h.build(building));
                        } catch (IllegalStateException injected) {
                            h.runtime.buildFailed(building, injected);
                        }
                        building = null;
                    } else if (op < 82 && scan == null) {
                        scan = h.runtime.beginScan();
                        scanOrder = new ArrayList<>(source.snapshot().keySet());
                        scanCursor = 0;
                    } else if (op < 92 && scan != null) {
                        // Курсор скана читает текущее значение источника.
                        int batch = 1 + random.nextInt(8);
                        Map<Long, float[]> now = source.snapshot();
                        for (int i = 0; i < batch && scanCursor < scanOrder.size(); i++) {
                            Long k = scanOrder.get(scanCursor++);
                            if (now.containsKey(k)) scan.offer(k, now.get(k));
                        }
                        if (scanCursor >= scanOrder.size()) {
                            // Ключи, добавленные после старта, скан не видит — их принесли события.
                            assertThat(h.runtime.publishScan(scan)).isEqualTo(PublishResult.PUBLISHED);
                            scan = null;
                        }
                    }
                    if (scan == null && !lost) {
                        try (ReadView<Long> view = h.view()) {
                            source.assertSameContent(view);
                            float[] query = gaussian(random, spec.dimension());
                            source.assertExactTopK(query, 5, view.search(query, SearchParams.exactTopK(5)).hits());
                            source.assertValidHits(query, view.search(query, SearchParams.topK(5)).hits());
                        }
                    }
                    if (scan == null && lost && !h.status().scanOpen() && random.nextInt(4) == 0) {
                        h.ready(source.snapshot());                  // сверка после пропуска
                        lost = h.status().repairPending();
                    }
                }
                if (building != null) {
                    h.runtime.publish(building, h.build(building));
                }
                if (scan != null) {
                    scan.close();
                }
                h.ready(source.snapshot());
                try (ReadView<Long> view = h.view()) {
                    source.assertSameContent(view);
                }
            }
        }
    }

    @Test
    void algorithmDoesNotModifyCoreOwnedVectorsOrQuery() throws Exception {
        VectorSpec spec = new VectorSpec(8, Metric.SQUARED_L2);
        Random random = new Random(42);
        Map<Long, float[]> source = new HashMap<>();
        for (long key = 0; key < 200; key++) {
            source.put(key, gaussian(random, spec.dimension()));
        }
        try (RuntimeHarness h = harness(spec).ready(source)) {
            Map<Long, float[]> before = h.content();
            h.optimize();
            float[] query = gaussian(random, spec.dimension());
            float[] queryCopy = query.clone();
            for (int i = 0; i < 20; i++) {
                h.search(query, 10);
            }
            Map<Long, float[]> after = h.content();

            assertThat(query).containsExactly(queryCopy);
            assertThat(after.keySet()).isEqualTo(before.keySet());
            before.forEach((key, vector) -> assertThat(after.get(key)).as("vector %s", key).containsExactly(vector));
        }
    }

    // ------------------------------------------------------- helpers

    /** Обновление первого вектора и захват сборки — аналог старой «паузы после захвата». */
    protected static BuildToken<Long> startUpdatingFirstVector(RuntimeHarness h) {
        h.runtime.upsert(1L, v(-1, 0, 0));
        return h.capture();
    }

    protected static void assertFirstVectorDistance(RuntimeHarness h, float[] query, double expected) {
        assertThat(h.search(query, 10)).filteredOn(neighbor -> neighbor.key() == 1L)
                .singleElement().satisfies(neighbor -> assertThat(neighbor.distance()).isCloseTo(expected, within(1e-6)));
    }

    protected static Map<Long, float[]> initialVectors() {
        return Map.of(1L, v(1, 0, 0), 2L, v(0, 1, 0), 3L, v(0, 0, 1));
    }

    protected static float[] v(float x, float y, float z) {
        return new float[]{x, y, z};
    }

    protected static float[] oneHot(int i) {
        float[] vector = new float[16];
        vector[i] = 1f;
        return vector;
    }

    protected static float[] gaussian(Random random, int dimension) {
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            vector[i] = (float) random.nextGaussian();
        }
        return vector;
    }
}
