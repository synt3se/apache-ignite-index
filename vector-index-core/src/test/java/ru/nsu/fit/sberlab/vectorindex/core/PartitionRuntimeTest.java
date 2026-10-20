package ru.nsu.fit.sberlab.vectorindex.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ru.nsu.fit.sberlab.vectorindex.core.algorithm.BuildContext;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.SearchStructure;

/** Протокол runtime: скан, ревизия базы, liveCount, токены, виды. Алгоритм — счётный Flat. */
@Timeout(30)
class PartitionRuntimeTest {
    private static final VectorSpec L2 = new VectorSpec(2, Metric.SQUARED_L2);

    private final CountingAlgorithm algorithm = new CountingAlgorithm();
    private final PartitionRuntime<Long> runtime =
            PartitionRuntime.create(RuntimeConfig.of("test", 0, L2, algorithm));

    // ------------------------------------------------------------ фазы и скан

    @Test
    void runtimeIsLoadingUntilFirstScanPublishesButAcceptsChangesImmediately() {
        assertThat(runtime.status().phase()).isEqualTo(Phase.LOADING);
        assertThat(runtime.upsert(1L, v(1, 0)).status()).isEqualTo(ApplyResult.Status.APPLIED);
        try (ReadView<Long> view = view()) {
            assertThat(view.phase()).isEqualTo(Phase.LOADING);
            assertThat(view.status().searchable()).isFalse();
        }
        ScanSession<Long> scan = runtime.beginScan();
        scan.offer(2L, v(0, 1));
        assertThat(runtime.publishScan(scan)).isEqualTo(PublishResult.PUBLISHED);

        assertThat(runtime.status().phase()).isEqualTo(Phase.READY);
        // Запись 1 применена до старта скана — скан её не прочитал, и она уступает базе скана.
        assertThat(content()).containsOnlyKeys(2L);
    }

    @Test
    void changesDuringScanOverrideScannedValues() {
        ScanSession<Long> scan = runtime.beginScan();
        scan.offer(1L, v(1, 0));
        scan.offer(2L, v(0, 1));
        runtime.upsert(1L, v(5, 5));
        runtime.delete(2L);
        runtime.upsert(3L, v(3, 3));
        runtime.publishScan(scan);

        assertThat(content()).containsOnlyKeys(1L, 3L);
        assertThat(content().get(1L)).containsExactly(5, 5);
        assertThat(size()).isEqualTo(2);
    }

    @Test
    void entriesAppliedBeforeScanStartYieldToTheScannedValue() {
        ready(Map.of(7L, v(0, 0)));
        runtime.upsert(7L, v(1, 1));          // применено, но следующее событие потеряно
        runtime.requestRepair(RepairReason.APPLIER_OVERFLOW);

        ScanSession<Long> scan = runtime.beginScan();
        scan.offer(7L, v(2, 2));              // источник уже содержит v2
        runtime.publishScan(scan);

        assertThat(content().get(7L)).containsExactly(2, 2);
        assertThat(runtime.status().repairPending()).isFalse();
    }

    @Test
    void deduplicationUsesTheSameResolutionAsSearch() {
        ready(Map.of(5L, v(0, 0)));
        runtime.upsert(5L, v(1, 1));                      // active: v1
        ScanSession<Long> scan = runtime.beginScan();
        scan.offer(5L, v(2, 2));
        runtime.publishScan(scan);                        // база: v2, active v1 больше не действует

        ApplyResult back = runtime.upsert(5L, v(1, 1));   // обратно на v1

        assertThat(back.status()).isEqualTo(ApplyResult.Status.APPLIED);
        assertThat(content().get(5L)).containsExactly(1, 1);
        assertThat(runtime.upsert(5L, v(1, 1)).status()).isEqualTo(ApplyResult.Status.UNCHANGED);
    }

    @Test
    void writesDuringOpenScanAreNeverShortCircuited() {
        ready(Map.of(1L, v(1, 1)));
        ScanSession<Long> scan = runtime.beginScan();
        scan.offer(1L, v(0, 0));                   // скан прочитал промежуточное значение
        // Событие о возврате к v1: текущий вид уже v1, но будущая база скана — нет.
        assertThat(runtime.upsert(1L, v(1, 1)).status()).isEqualTo(ApplyResult.Status.APPLIED);
        scan.offer(2L, v(2, 2));
        assertThat(runtime.delete(2L).status()).isEqualTo(ApplyResult.Status.APPLIED); // ключа ещё нет в виде
        runtime.publishScan(scan);

        assertThat(content()).containsOnlyKeys(1L);
        assertThat(content().get(1L)).containsExactly(1, 1);
    }

    @Test
    void scanFindsLostInsertsUpdatesAndDeletesAndCountsThemExactly() {
        ready(Map.of(1L, v(1, 1), 2L, v(2, 2), 3L, v(3, 3)));
        // Источник: 1 удалён, 3 изменён, 4 добавлен — события потеряны.
        ScanSession<Long> scan = runtime.beginScan();
        scan.offer(2L, v(2, 2));
        scan.offer(3L, v(9, 9));
        runtime.upsert(5L, v(5, 5));               // параллельная вставка
        scan.offer(4L, v(4, 4));
        runtime.delete(4L);                        // и удаление уже прочитанного сканом
        runtime.publishScan(scan);

        assertThat(content()).containsOnlyKeys(2L, 3L, 5L);
        assertThat(content().get(3L)).containsExactly(9, 9);
        assertThat(size()).isEqualTo(3);
    }

    @Test
    void liveCountStaysExactUnderConcurrentWritesDuringScanPublication() throws Exception {
        Random random = new Random(7);
        for (int round = 0; round < 50; round++) {
            PartitionRuntime<Long> rt = PartitionRuntime.create(RuntimeConfig.of("race", 0, L2, algorithm));
            Map<Long, float[]> base = new HashMap<>();
            for (long key = 0; key < 200; key++) {
                base.put(key, v(key, 1));
            }
            publishScan(rt, base);
            ScanSession<Long> scan = rt.beginScan();
            for (long key = 0; key < 200; key += 2) {
                scan.offer(key, v(key, 2));
            }
            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong written = new AtomicLong();
            long seed = random.nextLong();
            Thread writer = new Thread(() -> {
                Random r = new Random(seed);
                while (!stop.get()) {
                    long key = r.nextInt(300);
                    if (r.nextBoolean()) rt.upsert(key, v(key, r.nextInt(5))); else rt.delete(key);
                    written.incrementAndGet();
                }
            });
            writer.start();
            // Длинный журнал скана — обход вне замка идёт, пока писатель продолжает писать.
            while (written.get() < 5_000) {
                Thread.onSpinWait();
            }
            rt.publishScan(scan);
            stop.set(true);
            writer.join();

            try (ReadView<Long> view = rt.openView().orElseThrow()) {
                long[] recount = {0};
                view.forEachLive((key, vector) -> recount[0]++);
                assertThat(view.size()).as("round %d", round).isEqualTo(recount[0]);
            }
            rt.close();
        }
    }

    @Test
    void writesBetweenTheOutOfLockSnapshotAndTheLockAreCountedUnderTheLock() {
        PartitionRuntime<Object> rt = PartitionRuntime.create(RuntimeConfig.of("window", 0, L2, algorithm));
        ScanSession<Object> initial = rt.beginScan();
        initial.offer(1L, v(1, 1));
        initial.offer(2L, v(2, 2));
        rt.publishScan(initial);

        ScanSession<Object> scan = rt.beginScan();
        scan.offer(1L, v(1, 1));
        scan.offer(2L, v(2, 2));
        // Ключ, записанный во время скана: его hashCode при обходе журнала вне замка
        // вызывает запись — ровно в окне между снимком состояния и замком писателей.
        AtomicBoolean armed = new AtomicBoolean();
        Object trigger = new Object() {
            @Override public int hashCode() {
                if (armed.compareAndSet(true, false)) {
                    rt.upsert(100L, v(100, 100));
                    rt.delete(2L);
                    rt.upsert(1L, v(7, 7));
                }
                return 31;
            }
        };
        rt.upsert(trigger, v(3, 3));
        armed.set(true);
        assertThat(rt.publishScan(scan)).isEqualTo(PublishResult.PUBLISHED);

        assertThat(armed).as("the write window was exercised").isFalse();
        try (ReadView<Object> view = rt.openView().orElseThrow()) {
            Map<Object, float[]> live = new HashMap<>();
            view.forEachLive(live::put);
            assertThat(live).containsOnlyKeys(1L, 100L, trigger);
            assertThat(live.get(1L)).containsExactly(7, 7);
            assertThat(view.size()).isEqualTo(3);
        }
    }

    // ------------------------------------------------------------ жизненный цикл скана

    @Test
    void onlyTheLatestScanSessionCanPublishAndLateCloseOfOldOneIsHarmless() {
        ready(Map.of(1L, v(1, 1)));
        ScanSession<Long> first = runtime.beginScan();
        first.offer(1L, v(0, 0));
        ScanSession<Long> second = runtime.beginScan();
        second.offer(2L, v(2, 2));

        assertThat(first.active()).isFalse();
        assertThat(first.offer(3L, v(3, 3))).isEqualTo(ApplyResult.Status.CLOSED);
        assertThat(runtime.publishScan(first)).isEqualTo(PublishResult.REJECTED_STALE);
        first.close();
        assertThat(runtime.status().scanOpen()).as("old close must not abort the new scan").isTrue();
        assertThat(runtime.publishScan(second)).isEqualTo(PublishResult.PUBLISHED);
        assertThat(content()).containsOnlyKeys(2L);
        assertThat(runtime.publishScan(second)).isEqualTo(PublishResult.REJECTED_STALE);
    }

    @Test
    void captureIsRefusedWhileScanIsOpenAndAllowedAfterAbort() {
        ready(Map.of(1L, v(1, 1)));
        runtime.upsert(2L, v(2, 2));
        ScanSession<Long> scan = runtime.beginScan();
        assertThat(runtime.capture()).isEmpty();
        scan.close();
        assertThat(runtime.status().scanOpen()).isFalse();
        Optional<BuildToken<Long>> token = runtime.capture();
        assertThat(token).isPresent();
        token.get().close();
    }

    @Test
    void buildCapturedBeforeScanThatFinishesFirstIsPublishedThenReplacedByScan() throws Exception {
        ready(Map.of(1L, v(1, 1)));
        runtime.upsert(2L, v(2, 2));
        BuildToken<Long> token = runtime.capture().orElseThrow();
        ScanSession<Long> scan = runtime.beginScan();
        scan.offer(1L, v(1, 1));
        scan.offer(3L, v(3, 3));

        assertThat(runtime.publish(token, build(token))).isEqualTo(PublishResult.PUBLISHED);
        assertThat(content()).containsOnlyKeys(1L, 2L);
        assertThat(runtime.publishScan(scan)).isEqualTo(PublishResult.PUBLISHED);

        // 2 применён до старта скана, скан его не видел: источник его уже не содержит.
        assertThat(content()).containsOnlyKeys(1L, 3L);
        assertThat(algorithm.last().closed).as("replaced build released").isTrue();
    }

    @Test
    void buildCapturedBeforeScanThatFinishesAfterScanIsRejectedAndReleased() throws Exception {
        ready(Map.of(1L, v(1, 1)));
        runtime.upsert(2L, v(2, 2));
        BuildToken<Long> token = runtime.capture().orElseThrow();
        ScanSession<Long> scan = runtime.beginScan();
        scan.offer(1L, v(1, 1));
        runtime.upsert(4L, v(4, 4));
        assertThat(runtime.publishScan(scan)).isEqualTo(PublishResult.PUBLISHED);

        SearchStructure late = build(token);
        assertThat(runtime.publish(token, late)).isEqualTo(PublishResult.REJECTED_STALE);
        assertThat(algorithm.last().closed).isTrue();
        assertThat(token.closed()).isTrue();
        assertThat(content()).containsOnlyKeys(1L, 4L);
        assertThat(runtime.status().optimization()).isEqualTo(OptimizationState.NOT_BUILT);
        assertThat(runtime.capture()).as("BUILDING released for the next build").isPresent();
    }

    // ------------------------------------------------------------ токены и ресурсы

    @Test
    void successfulPublicationReleasesThePreviousStructureAfterTheLastView() throws Exception {
        ready(Map.of(1L, v(1, 1)));
        optimize();
        CountingAlgorithm.Tracked first = algorithm.last();
        ReadView<Long> held = view();
        runtime.upsert(2L, v(2, 2));
        optimize();

        assertThat(first.closed).as("still held by the open view").isFalse();
        assertThat(held.search(v(1, 1), SearchParams.topK(5)).hits()).extracting(Hit::key).containsExactly(1L);
        held.close();
        assertThat(first.closed).isTrue();
        assertThat(algorithm.last().closed).isFalse();
    }

    @Test
    void failedBuildKeepsDataAndFrozenLayersAndReleasesBuilding() throws Exception {
        ready(Map.of(1L, v(1, 1)));
        optimize();
        runtime.upsert(2L, v(2, 2));
        BuildToken<Long> token = runtime.capture().orElseThrow();
        algorithm.failNextBuild = true;
        try {
            build(token);
        } catch (IllegalStateException expected) {
            runtime.buildFailed(token, expected);
        }

        RuntimeStatus failed = runtime.status();
        assertThat(token.closed()).isTrue();
        assertThat(failed.optimization()).isEqualTo(OptimizationState.FAILED);
        assertThat(failed.frozenLayers()).isEqualTo(1);
        assertThat(failed.buildFailures()).isEqualTo(1);
        assertThat(content()).containsOnlyKeys(1L, 2L);

        runtime.upsert(3L, v(3, 3));
        BuildToken<Long> retry = runtime.capture().orElseThrow();
        assertThat(runtime.status().frozenLayers()).as("next capture adds a layer").isEqualTo(2);
        assertThat(runtime.publish(retry, build(retry))).isEqualTo(PublishResult.PUBLISHED);
        assertThat(runtime.status().frozenLayers()).isZero();
        assertThat(runtime.status().optimization()).isEqualTo(OptimizationState.BUILT);
        assertThat(content()).containsOnlyKeys(1L, 2L, 3L);
    }

    @Test
    void closeDuringBuildRejectsPublicationAndReleasesEverythingOnlyAfterTheToken() throws Exception {
        ready(Map.of(1L, v(1, 1)));
        optimize();
        CountingAlgorithm.Tracked base = algorithm.last();
        runtime.upsert(2L, v(2, 2));
        BuildToken<Long> token = runtime.capture().orElseThrow();
        runtime.close();

        assertThat(base.closed).as("token still holds the captured base").isFalse();
        assertThat(runtime.upsert(3L, v(3, 3)).status()).isEqualTo(ApplyResult.Status.CLOSED);
        assertThat(runtime.openView()).isEmpty();
        assertThat(runtime.publish(token, build(token))).isEqualTo(PublishResult.REJECTED_STALE);
        assertThat(base.closed).isTrue();
        assertThat(algorithm.last().closed).isTrue();
        assertThat(runtime.beginScan().active()).isFalse();
    }

    @Test
    void viewContentDoesNotChangeAfterOpening() {
        ready(Map.of(1L, v(1, 1), 2L, v(2, 2)));
        try (ReadView<Long> view = view()) {
            runtime.upsert(1L, v(9, 9));
            runtime.delete(2L);
            runtime.upsert(3L, v(3, 3));
            Map<Long, float[]> seen = new LinkedHashMap<>();
            view.forEachLive(seen::put);

            assertThat(seen).containsOnlyKeys(1L, 2L);
            assertThat(view.get(1L)).containsExactly(1, 1);
            assertThat(view.size()).isEqualTo(2);
        }
        assertThat(content()).containsOnlyKeys(1L, 3L);
    }

    @Test
    void publicReadsReturnCopies() {
        ready(Map.of(1L, v(1, 1)));
        try (ReadView<Long> view = view()) {
            view.get(1L)[0] = 100;
            view.forEachLive((key, vector) -> vector[1] = 100);
            assertThat(view.get(1L)).containsExactly(1, 1);
        }
    }

    // ------------------------------------------------------------ отказ и бюджет

    @Test
    void invalidUpdateRemovesPreviousVectorFromProjection() {
        ready(Map.of(1L, v(1, 1), 2L, v(2, 2)));

        ApplyResult result = runtime.upsert(1L, new float[]{Float.NaN, 0});

        assertThat(result.status()).isEqualTo(ApplyResult.Status.REJECTED);
        assertThat(result.reason()).isEqualTo(RejectReason.NON_FINITE);
        assertThat(content()).containsOnlyKeys(2L);
        assertThat(size()).isEqualTo(1);
        try (ReadView<Long> view = view()) {
            assertThat(view.search(v(1, 1), SearchParams.topK(5)).hits()).extracting(Hit::key).containsExactly(2L);
            assertThat(view.status().rejectedVectors()).containsEntry(RejectReason.NON_FINITE, 1L);
            assertThat(view.status().rejectedTotal()).isEqualTo(1);
        }
        assertThat(runtime.upsert(9L, new float[3]).status()).as("absent key: counted, nothing written")
                .isEqualTo(ApplyResult.Status.REJECTED);
        assertThat(runtime.status().rejectedVectors()).containsEntry(RejectReason.WRONG_DIMENSION, 1L);
    }

    @Test
    void invalidVectorDuringScanIsExcludedAndCounted() {
        ScanSession<Long> scan = runtime.beginScan();
        assertThat(scan.offer(1L, v(1, 1))).isEqualTo(ApplyResult.Status.APPLIED);
        assertThat(scan.offer(2L, new float[]{Float.POSITIVE_INFINITY, 0})).isEqualTo(ApplyResult.Status.REJECTED);
        assertThat(scan.offer(3L, new float[5])).isEqualTo(ApplyResult.Status.REJECTED);
        runtime.publishScan(scan);

        assertThat(content()).containsOnlyKeys(1L);
        assertThat(runtime.status().rejectedTotal()).isEqualTo(2);
        assertThatIllegalArgumentException().isThrownBy(() -> runtime.beginScan().offer(4L, null));
    }

    @Test
    void exhaustedBudgetYieldsNoHitsInsteadOfAPartialPartition() {
        ready(Map.of(1L, v(1, 1)));
        AtomicLong now = new AtomicLong();
        Budget budget = Budget.remaining(now::get, 10);
        now.set(10);
        try (ReadView<Long> view = view()) {
            LocalSearchResult<Long> result = view.search(v(1, 1), SearchParams.topK(1).withBudget(budget));
            assertThat(result.completion()).isEqualTo(Completion.BUDGET_EXHAUSTED);
            assertThat(result.hits()).isEmpty();
        }
    }

    @Test
    void underfilledAnnFallsBackToExactSearchWhileBudgetAllows() throws Exception {
        ready(Map.of(1L, v(1, 1), 2L, v(2, 2), 3L, v(3, 3)));
        optimize();
        algorithm.returnNoCandidates = true;
        try (ReadView<Long> view = view()) {
            LocalSearchResult<Long> result = view.search(v(0, 0), SearchParams.topK(2));
            assertThat(result.hits()).extracting(Hit::key).containsExactly(1L, 2L);
            assertThat(result.annUnderfilled()).isFalse();
        }
        assertThat(runtime.status().exactFallbacks()).isEqualTo(1);
    }

    @Test
    void repairRequestedDuringRunningScanSurvivesItsCompletion() {
        ScanSession<Long> scan = runtime.beginScan();
        runtime.requestRepair(RepairReason.APPLIER_OVERFLOW);
        runtime.publishScan(scan);
        assertThat(runtime.repairPending()).isTrue();

        ScanSession<Long> again = runtime.beginScan();
        runtime.publishScan(again);
        assertThat(runtime.repairPending()).isFalse();
        assertThat(runtime.status().repairCompleted()).isEqualTo(1);
    }

    @Test
    void searchDistancesUseTheSharedContract() {
        ready(Map.of(1L, v(1, 0), 2L, v(0, 3)));
        try (ReadView<Long> view = view()) {
            assertThat(view.search(v(0, 0), SearchParams.exactTopK(2)).hits())
                    .extracting(Hit::key, Hit::distance)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, 1.0),
                            org.assertj.core.groups.Tuple.tuple(2L, 9.0));
            assertThat(view.search(v(0, 0), SearchParams.topK(0)).hits()).isEmpty();
        }
        assertThat(runtime.status().liveCount()).isEqualTo(2);
        assertThat(content().get(2L)[1]).isCloseTo(3f, within(1e-6f));
    }

    // ------------------------------------------------------------ helpers

    private void ready(Map<Long, float[]> records) {
        publishScan(runtime, records);
    }

    private static void publishScan(PartitionRuntime<Long> rt, Map<Long, float[]> records) {
        ScanSession<Long> scan = rt.beginScan();
        records.forEach(scan::offer);
        assertThat(rt.publishScan(scan)).isEqualTo(PublishResult.PUBLISHED);
    }

    private void optimize() throws Exception {
        BuildToken<Long> token = runtime.capture().orElseThrow();
        assertThat(runtime.publish(token, build(token))).isEqualTo(PublishResult.PUBLISHED);
    }

    private SearchStructure build(BuildToken<Long> token) throws Exception {
        return algorithm.build(token.input(), BuildContext.of(java.util.concurrent.ForkJoinPool.commonPool()));
    }

    private ReadView<Long> view() {
        return runtime.openView().orElseThrow();
    }

    private Map<Long, float[]> content() {
        Map<Long, float[]> out = new HashMap<>();
        try (ReadView<Long> view = view()) {
            view.forEachLive(out::put);
        }
        return out;
    }

    private long size() {
        try (ReadView<Long> view = view()) {
            return view.size();
        }
    }

    private static float[] v(float x, float y) {
        return new float[]{x, y};
    }
}
