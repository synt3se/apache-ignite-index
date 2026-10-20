package ru.nsu.fit.sberlab.vectorindex.node.index;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.ignite.logger.NullLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ru.nsu.fit.sberlab.vectorindex.common.ScoredVector;
import ru.nsu.fit.sberlab.vectorindex.common.indextype.IndexType;
import ru.nsu.fit.sberlab.vectorindex.core.Phase;
import ru.nsu.fit.sberlab.vectorindex.core.maintenance.Backoff;
import ru.nsu.fit.sberlab.vectorindex.core.maintenance.BuildService;
import ru.nsu.fit.sberlab.vectorindex.testkit.ManualClock;
import ru.nsu.fit.sberlab.vectorindex.testkit.ManualDelayedExecutor;
import ru.nsu.fit.sberlab.vectorindex.testkit.ManualExecutor;
import ru.nsu.fit.sberlab.vectorindex.testkit.RuntimeHarness;

/**
 * Адаптер на событиях поверх ядра. Источник — карта в памяти, сканы и сборки — ручные
 * executor, применение — настоящий {@link StripedApplier}. Зависимости внедряются через
 * конструктор: reflection и обращения к закрытым полям не нужны.
 * Сценарии перенесены из {@code PartitionIndexManagerTest}.
 */
@Timeout(15)
class IgniteIndexAdapterTest {

    @Test
    void scansOwnedPartitionsAndMergesTheirTopKByDistance() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.owned.set(new int[]{0, 1});
            fixture.source.put(10, 1, 0);
            fixture.source.put(12, -1, 0);
            fixture.source.put(31, 0, 1);
            fixture.source.putWithoutVector(14);

            fixture.rebuildAll();

            assertThat(fixture.adapter.activePartitionsCount()).isEqualTo(2);
            assertThat(fixture.adapter.searchLocal(new float[]{1, 0}, 2))
                    .extracting(ScoredVector::id, ScoredVector::distance)
                    .containsExactly(tuple(10L, 0.0), tuple(31L, 1.0));
            assertThat(fixture.adapter.localStats().liveVectors).isEqualTo(3);
        }
    }

    @Test
    void queuedEventReadsCurrentCacheValueInsteadOfTheValueAtEventTime() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.put(10, 1, 0);
            fixture.rebuildAll();
            fixture.blockApplier();
            fixture.source.put(10, -1, 0);
            fixture.adapter.onKeyChanged(10L);
            fixture.source.put(10, 0, 1);

            fixture.releaseApplier();

            fixture.awaitSearch(new float[]{0, 1}, new ScoredVector(10, 0));
        }
    }

    @Test
    void repeatedUpdatesAndRemovalsConvergeWithoutDuplicateOrStaleVectors() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.put(10, 1, 0);
            fixture.rebuildAll();

            fixture.source.put(10, 0, 1);
            fixture.eventAndAwaitApplied(10, 1);
            fixture.eventAndAwaitApplied(10, 2);
            assertThat(fixture.adapter.searchLocal(new float[]{0, 1}, 10))
                    .extracting(ScoredVector::id, ScoredVector::distance)
                    .containsExactly(tuple(10L, 0.0));

            fixture.source.remove(10);
            fixture.eventAndAwaitApplied(10, 3);
            fixture.eventAndAwaitApplied(10, 4);
            assertThat(fixture.adapter.searchLocal(new float[]{0, 1}, 10)).isEmpty();

            fixture.source.put(10, 1, 0);
            fixture.eventAndAwaitApplied(10, 5);
            fixture.source.putWithoutVector(10);
            fixture.eventAndAwaitApplied(10, 6);
            assertThat(fixture.adapter.searchLocal(new float[]{1, 0}, 10)).isEmpty();
        }
    }

    @Test
    void reconcileRemovesSearchResultsForPartitionsThatMovedToAnotherNode() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.owned.set(new int[]{0, 1});
            fixture.source.put(10, 1, 0);
            fixture.source.put(31, 0, 1);
            fixture.rebuildAll();
            var moved = fixture.adapter.runtime(0);

            fixture.source.owned.set(new int[]{1});
            fixture.adapter.forceReconcile(false);
            fixture.scans.runAll();

            assertThat(fixture.adapter.activePartitionsCount()).isEqualTo(1);
            assertThat(fixture.adapter.searchLocal(new float[]{1, 0}, 10))
                    .extracting(ScoredVector::id, ScoredVector::distance)
                    .containsExactly(tuple(31L, 1.0));
            assertThat(moved.isClosed()).as("the old ownership episode is closed").isTrue();
        }
    }

    @Test
    void overflowWhileIdleIsRepairedByReconcileEvenWithoutAnotherCacheEvent() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.put(10, 1, 0);
            fixture.rebuildAll();
            fixture.saturateApplier();
            fixture.source.put(10, 0, 1);
            fixture.adapter.onKeyChanged(10L); // This event cannot enter the full queue.

            fixture.releaseApplier();
            fixture.adapter.forceReconcile(false);
            fixture.scans.runAll();

            assertThat(fixture.adapter.searchLocal(new float[]{0, 1}, 10))
                    .extracting(ScoredVector::id, ScoredVector::distance)
                    .containsExactly(tuple(10L, 0.0));
            assertThat(fixture.adapter.localStats().applierOverflowsTotal).isEqualTo(1);
        }
    }

    @Test
    void acceptedChangesDuringScanAreReplayedBeforeTheNewIndexIsPublished() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.put(10, 1, 0);
            fixture.source.put(20, 0, 1);
            fixture.rebuildAll();
            FakeVectorSource.ScanBarrier scan = fixture.source.blockNextScanAfterItsLastEntry();
            fixture.adapter.forceReconcile(true);
            Future<?> inFlight = fixture.scanWorker.submit(fixture.scans::runNext);
            assertThat(scan.finishedReading.await(3, TimeUnit.SECONDS)).isTrue();

            fixture.source.put(10, 0, -1);
            fixture.adapter.onKeyChanged(10L);
            fixture.flushApplier();
            fixture.source.remove(20);
            fixture.adapter.onKeyChanged(20L);
            fixture.flushApplier();
            fixture.source.put(30, 1, 0);
            fixture.adapter.onKeyChanged(30L);
            fixture.flushApplier();

            scan.release.countDown();
            inFlight.get(3, TimeUnit.SECONDS);

            assertThat(fixture.adapter.searchLocal(new float[]{0, -1}, 10))
                    .extracting(ScoredVector::id, ScoredVector::distance)
                    .containsExactly(tuple(10L, 0.0), tuple(30L, 1.0));
        }
    }

    @Test
    @DisplayName("К2: overflow during a running scan must survive reconcile and trigger recovery")
    void overflowDuringRebuildIsNotForgottenByConcurrentReconcile() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.put(10, 1, 0);
            fixture.rebuildAll();
            FakeVectorSource.ScanBarrier scan = fixture.source.blockNextScanAfterItsLastEntry();
            fixture.adapter.forceReconcile(true);
            Future<?> inFlight = fixture.scanWorker.submit(fixture.scans::runNext);
            assertThat(scan.finishedReading.await(3, TimeUnit.SECONDS)).isTrue();
            fixture.saturateApplier();
            fixture.source.put(10, 0, 1);
            fixture.adapter.onKeyChanged(10L); // Lost after this key has already been scanned.
            fixture.adapter.forceReconcile(false); // The original rebuild is still queued/running.

            scan.release.countDown();
            inFlight.get(3, TimeUnit.SECONDS);
            fixture.releaseApplier();
            fixture.adapter.forceReconcile(false);
            fixture.scans.runAll();

            // No new event or forced full rebuild: periodic reconcile must suffice to converge.
            assertThat(fixture.adapter.searchLocal(new float[]{0, 1}, 10))
                    .extracting(ScoredVector::id, ScoredVector::distance)
                    .containsExactly(tuple(10L, 0.0));
        }
    }

    @Test
    @DisplayName("К5: a managed bulk pause finishes with a full rebuild on resume")
    void resumeAfterBulkClearIndexesWritesWhoseEventsWerePaused() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.put(10, 1, 0);
            fixture.rebuildAll();
            fixture.adapter.pauseIndexing(60_000);
            fixture.adapter.clearAll();
            fixture.source.values.clear();
            fixture.source.put(20, 0, 1);
            fixture.adapter.onKeyChanged(20L);
            fixture.adapter.forceReconcile(false);
            fixture.scans.runAll();
            assertThat(fixture.adapter.searchLocal(new float[]{0, 1}, 10)).isEmpty();

            fixture.adapter.resumeIndexing();
            fixture.scans.runAll();

            assertThat(fixture.adapter.searchLocal(new float[]{0, 1}, 10))
                    .extracting(ScoredVector::id, ScoredVector::distance)
                    .containsExactly(tuple(20L, 0.0));
        }
    }

    // ------------------------------------------------------------ новые сценарии адаптера

    @Test
    void readFailureRequestsRepairAndTheNextScanConverges() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.put(10, 1, 0);
            fixture.rebuildAll();
            fixture.source.put(10, 0, 1);
            fixture.source.failingReads.add(10L);
            fixture.eventAndAwaitApplied(10, 1);
            assertThat(fixture.adapter.localStats().repairPendingPartitions).isEqualTo(1);

            fixture.source.failingReads.clear();
            fixture.adapter.forceReconcile(false);
            fixture.scans.runAll();

            assertThat(fixture.adapter.searchLocal(new float[]{0, 1}, 10))
                    .extracting(ScoredVector::id, ScoredVector::distance).containsExactly(tuple(10L, 0.0));
            assertThat(fixture.adapter.localStats().repairPendingPartitions).isZero();
            assertThat(fixture.adapter.localStats().applyErrorsTotal).isEqualTo(1);
        }
    }

    @Test
    void newPartitionIsLoadingUntilItsFirstScanPublishes() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.put(10, 1, 0);
            fixture.adapter.forceReconcile(false);       // runtime создан, скан стоит в очереди

            assertThat(fixture.adapter.runtime(0).status().phase()).isEqualTo(Phase.LOADING);
            assertThat(fixture.adapter.searchLocal(new float[]{1, 0}, 10)).isEmpty();
            assertThat(fixture.adapter.localStats().loadingPartitions).isEqualTo(1);

            fixture.scans.runAll();
            assertThat(fixture.adapter.searchLocal(new float[]{1, 0}, 10)).extracting(ScoredVector::id)
                    .containsExactly(10L);
        }
    }

    @Test
    @DisplayName("A queued scan of the previous ownership episode must not swallow the first scan of the next one")
    void partitionThatLeftAndReturnedWhileItsOldScanWasQueuedStillGetsItsFirstScan() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.put(10, 1, 0);
            fixture.adapter.forceReconcile(false);        // эпизод 1: первоначальный скан в очереди
            var first = fixture.adapter.runtime(0);
            fixture.source.owned.set(new int[0]);
            fixture.adapter.forceReconcile(false);        // партиция ушла: эпизод 1 закрыт
            fixture.source.owned.set(new int[]{0});
            fixture.adapter.forceReconcile(false);        // вернулась: эпизод 2 ждёт своего скана
            var second = fixture.adapter.runtime(0);
            assertThat(second).isNotSameAs(first);
            assertThat(first.isClosed()).isTrue();

            // Задание эпизода 1 завершается без работы, скан эпизода 2 стоит в очереди сам по себе:
            // без повторной сверки (её страховку проверяет следующий тест).
            fixture.scans.runAll();

            assertThat(second.phase()).isEqualTo(Phase.READY);
            assertThat(fixture.adapter.searchLocal(new float[]{1, 0}, 10)).extracting(ScoredVector::id)
                    .containsExactly(10L);
        }
    }

    @Test
    void firstScanRejectedByThePoolIsRetriedByTheNextReconcile() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.put(10, 1, 0);
            fixture.scans.rejectNext(1);
            fixture.adapter.forceReconcile(false);        // runtime создан, скан не принят пулом
            assertThat(fixture.adapter.runtime(0).phase()).isEqualTo(Phase.LOADING);
            assertThat(fixture.adapter.runtime(0).repairPending()).isFalse();
            assertThat(fixture.scans.pending()).isZero();

            fixture.adapter.forceReconcile(false);
            fixture.scans.runAll();

            assertThat(fixture.adapter.runtime(0).phase()).isEqualTo(Phase.READY);
            assertThat(fixture.adapter.searchLocal(new float[]{1, 0}, 10)).extracting(ScoredVector::id)
                    .containsExactly(10L);
        }
    }

    @Test
    void unsupportedKeyTypeIsCountedNotSilentlyIgnored() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.rebuildAll();
            fixture.adapter.onKeyChanged("not-a-long");
            assertThat(fixture.adapter.localStats().unsupportedKeysTotal).isEqualTo(1);
        }
    }

    @Test
    void buildsRunOnTheSharedBuildPoolAndKeepContent() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.put(10, 1, 0);
            fixture.source.put(20, 0, 1);
            fixture.rebuildAll();
            fixture.source.put(30, -1, 0);
            fixture.eventAndAwaitApplied(30, 1);

            fixture.builds.runAll();

            assertThat(fixture.adapter.localStats().deltaEntries).isZero();
            assertThat(fixture.adapter.searchLocal(new float[]{-1, 0}, 1)).extracting(ScoredVector::id)
                    .containsExactly(30L);
        }
    }

    // ------------------------------------------------------------ fixture

    private static final class Fixture implements AutoCloseable {
        final FakeVectorSource source = new FakeVectorSource();
        final ManualExecutor scans = new ManualExecutor();
        final ManualExecutor builds = new ManualExecutor();
        final ManualClock clock = new ManualClock();
        final ManualDelayedExecutor delayed = new ManualDelayedExecutor(clock);
        final ExecutorService scanWorker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "adapter-test-scan");
            thread.setDaemon(true);
            return thread;
        });
        final StripedApplier applier = new StripedApplier(1, 1);
        final IgniteIndexAdapter adapter;
        private final CountDownLatch applierRelease = new CountDownLatch(1);

        Fixture() {
            BuildService buildService = new BuildService(builds, delayed,
                    Backoff.exponential(Duration.ofSeconds(1), Duration.ofSeconds(8)),
                    RuntimeHarness.BUILD_POOL, RuntimeHarness.BUILD_POOL);
            NodeResources resources = new NodeResources(applier, scans, Runnable::run, delayed, null, buildService,
                    List.of(applier::close));
            adapter = new IgniteIndexAdapter(IndexDefinition.fromLegacy("vectors", 2, IndexType.BRUTE_FORCE_INDEX,
                    new JVectorProperties()), source, resources, clock, new NullLogger());
        }

        void rebuildAll() {
            adapter.forceReconcile(true);
            scans.runAll();
        }

        void eventAndAwaitApplied(long key, long expectedTotal) {
            adapter.onKeyChanged(key);
            await().atMost(ofSeconds(3)).untilAsserted(() ->
                    assertThat(adapter.localStats().appliedTotal).isEqualTo(expectedTotal));
        }

        void awaitSearch(float[] query, ScoredVector expected) {
            await().atMost(ofSeconds(3)).untilAsserted(() ->
                    assertThat(adapter.searchLocal(query, 10))
                            .extracting(ScoredVector::id, ScoredVector::distance)
                            .containsExactly(tuple(expected.id(), expected.distance())));
        }

        void blockApplier() throws InterruptedException {
            CountDownLatch entered = new CountDownLatch(1);
            assertThat(applier.submit(0, () -> {
                entered.countDown();
                FakeVectorSource.await(applierRelease);
            })).isTrue();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        }

        void saturateApplier() throws InterruptedException {
            blockApplier();
            assertThat(applier.submit(0, () -> {})).isTrue();
        }

        void releaseApplier() {
            applierRelease.countDown();
        }

        void flushApplier() throws InterruptedException {
            CountDownLatch finished = new CountDownLatch(1);
            await().atMost(ofSeconds(3)).until(() -> applier.submit(0, finished::countDown));
            assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue();
        }

        @Override
        public void close() throws InterruptedException {
            applierRelease.countDown();
            source.releaseAllBarriers();
            scanWorker.shutdownNow();
            adapter.stop();
            applier.close();
            assertThat(scanWorker.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }
}
