package ru.nsu.fit.sberlab.vectorindex.node.index;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.ignite.logger.NullLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexHit;
import ru.nsu.fit.sberlab.vectorindex.common.dto.KeyType;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeBarrierProgress;
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
                    .extracting(h -> h.key, h -> h.distance)
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

            fixture.awaitSearch(new float[]{0, 1}, new IndexHit(10L, 0.0));
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
                    .extracting(h -> h.key, h -> h.distance)
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
                    .extracting(h -> h.key, h -> h.distance)
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
                    .extracting(h -> h.key, h -> h.distance)
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
                    .extracting(h -> h.key, h -> h.distance)
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
                    .extracting(h -> h.key, h -> h.distance)
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
                    .extracting(h -> h.key, h -> h.distance)
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
                    .extracting(h -> h.key, h -> h.distance).containsExactly(tuple(10L, 0.0));
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
            assertThat(fixture.adapter.searchLocal(new float[]{1, 0}, 10)).extracting(h -> h.key)
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
            assertThat(fixture.adapter.searchLocal(new float[]{1, 0}, 10)).extracting(h -> h.key)
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
            assertThat(fixture.adapter.searchLocal(new float[]{1, 0}, 10)).extracting(h -> h.key)
                    .containsExactly(10L);
        }
    }

    @Test
    void unsupportedKeyTypeIsCountedNotSilentlyIgnored() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.values.put(new java.util.Date(0), new SourceValue.Vector(new float[]{1, 0}));
            fixture.rebuildAll();                         // скан встречает ключ неподдерживаемого типа
            fixture.adapter.onKeyChanged(42);              // Integer: не Long, String или UUID

            assertThat(fixture.adapter.localStatus().unsupportedKeysTotal).isEqualTo(2);
            assertThat(fixture.adapter.searchLocal(new float[]{1, 0}, 10)).isEmpty();
        }
    }

    @Test
    void stringAndUuidKeysAreIndexedAndReturnedWithTheirOwnType() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.owned.set(new int[]{0, 1});
            java.util.UUID uuid = new java.util.UUID(7, 9);
            fixture.source.putKey("doc-1", 1, 0);
            fixture.rebuildAll();
            fixture.source.putKey(uuid, 0.9f, 0.1f);
            fixture.adapter.onKeyChanged(uuid);
            fixture.flushApplier();

            assertThat(fixture.adapter.searchLocal(new float[]{1, 0}, 10)).extracting(h -> h.key, IndexHit::keyType)
                    .containsExactly(tuple("doc-1", KeyType.STRING), tuple(uuid, KeyType.UUID));
            assertThat(fixture.adapter.localStatus().unsupportedKeysTotal).isZero();
        }
    }

    @Test
    void valueOfTheWrongTypeIsExcludedFromTheIndexAndCounted() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.put(10, 1, 0);
            fixture.source.putWrongType(20);
            fixture.rebuildAll();
            assertThat(fixture.adapter.localStatus().wrongTypeValuesTotal).as("seen by the scan").isEqualTo(1);

            fixture.source.putWrongType(10);               // был вектор, стало значение неверного типа
            fixture.eventAndAwaitApplied(10, 1);

            assertThat(fixture.adapter.searchLocal(new float[]{1, 0}, 10)).isEmpty();
            assertThat(fixture.adapter.localStatus().wrongTypeValuesTotal).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("Readiness counts an apply that is already running, not only the queue")
    void runningApplyIsVisibleInReadinessWhileTheQueueIsEmpty() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.put(10, 1, 0);
            fixture.rebuildAll();
            FakeVectorSource.ScanBarrier read = fixture.source.blockNextReadOf(10L);
            fixture.source.put(10, 0, 1);
            fixture.adapter.onKeyChanged(10L);
            assertThat(read.finishedReading.await(3, TimeUnit.SECONDS)).isTrue();

            assertThat(fixture.applier.backlog()).as("nothing is queued any more").isZero();
            assertThat(fixture.adapter.localStatus().pendingApplies).as("the apply is still running").isEqualTo(1);

            read.release.countDown();
            await().atMost(ofSeconds(3)).untilAsserted(() ->
                    assertThat(fixture.adapter.localStatus().pendingApplies).isZero());
            assertThat(fixture.adapter.searchLocal(new float[]{0, 1}, 1)).extracting(h -> h.key).containsExactly(10L);
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
            assertThat(fixture.adapter.searchLocal(new float[]{-1, 0}, 1)).extracting(h -> h.key)
                    .containsExactly(30L);
        }
    }

    // ------------------------------------------------------------ барьер загрузки (этап D)

    @Test
    @DisplayName("Barrier: an apply that read the old value before the last write must finish before the final scan")
    void barrierWaitsForAnApplyThatReadTheOldValueBeforeTheFinalScan() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.put(10, 1, 0);
            fixture.rebuildAll();
            FakeVectorSource.ScanBarrier stale = fixture.source.blockAfterReadOf(10L);
            fixture.source.put(10, 0, 1);                    // v1
            fixture.adapter.onKeyChanged(10L);                // обработчик прочитал v1 и задержался
            assertThat(stale.finishedReading.await(3, TimeUnit.SECONDS)).isTrue();
            fixture.source.put(10, -1, 0);                   // v2 — последняя запись; её событие потеряно

            Future<NodeBarrierProgress> begin = fixture.scanWorker.submit(() ->
                    fixture.adapter.beginBarrier("b1", false, TimeUnit.SECONDS.toNanos(5)));
            assertThatThrownBy(() -> begin.get(200, TimeUnit.MILLISECONDS))
                    .as("no tickets while an older apply is still running").isInstanceOf(TimeoutException.class);
            assertThat(fixture.scans.pending()).isZero();

            stale.release.countDown();                        // v1 применён до выдачи тикета
            assertThat(begin.get(3, TimeUnit.SECONDS).error).isNull();
            fixture.scans.runAll();                           // финальный скан читает v2

            assertThat(fixture.adapter.barrierProgress("b1").scannedPartitions).containsExactly(0);
            assertThat(fixture.adapter.searchLocal(new float[]{-1, 0}, 1))
                    .extracting(h -> h.key, h -> h.distance).containsExactly(tuple(10L, 0.0));
        }
    }

    @Test
    void ticketIssuedDuringAnOldScanIsClosedOnlyByTheNextScan() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.put(10, 1, 0);
            fixture.rebuildAll();
            FakeVectorSource.ScanBarrier old = fixture.source.blockNextScanAfterItsLastEntry();
            fixture.adapter.forceReconcile(true);
            Future<?> inFlight = fixture.scanWorker.submit(fixture.scans::runNext);
            assertThat(old.finishedReading.await(3, TimeUnit.SECONDS)).isTrue();
            fixture.source.put(10, 0, 1);                    // после того, как старый скан прочитал ключ

            assertThat(fixture.adapter.beginBarrier("b2", false, TimeUnit.SECONDS.toNanos(5)).error).isNull();
            old.release.countDown();
            inFlight.get(3, TimeUnit.SECONDS);

            assertThat(fixture.adapter.barrierProgress("b2").scannedPartitions)
                    .as("the scan begun before the ticket does not close it").isEmpty();
            fixture.scans.runAll();
            assertThat(fixture.adapter.barrierProgress("b2").scannedPartitions).containsExactly(0);
            assertThat(fixture.adapter.searchLocal(new float[]{0, 1}, 1))
                    .extracting(h -> h.key, h -> h.distance).containsExactly(tuple(10L, 0.0));
        }
    }

    @Test
    void replacingTheRuntimeInvalidatesTheBarrier() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.put(10, 1, 0);
            fixture.rebuildAll();
            fixture.adapter.beginBarrier("b3", false, TimeUnit.SECONDS.toNanos(5));
            fixture.scans.runAll();
            assertThat(fixture.adapter.barrierProgress("b3").scannedPartitions).containsExactly(0);

            fixture.source.owned.set(new int[0]);
            fixture.adapter.forceReconcile(false);             // эпизод владения закрыт
            fixture.source.owned.set(new int[]{0, 1});
            fixture.adapter.forceReconcile(false);             // новый runtime партиции 0 и пришедшая партиция 1
            fixture.scans.runAll();

            NodeBarrierProgress progress = fixture.adapter.barrierProgress("b3");
            assertThat(progress.invalidatedPartitions).as("replaced runtime").containsExactly(0);
            assertThat(progress.foreignPartitions).as("partition without a ticket").containsExactly(1);
            assertThat(progress.scannedPartitions).isEmpty();
            assertThat(fixture.adapter.barrierProgress("unknown").unknownBarrier).isTrue();
        }
    }

    @Test
    @DisplayName("Barrier forces a build of a delta below the automatic threshold; empty FLAT partitions complete too")
    void barrierForcesABuildOfASmallDeltaAndCompletesEmptyPartitions() throws Exception {
        JVectorProperties neverAutomatic = new JVectorProperties();
        neverAutomatic.setMinAdditionsBeforeRebuild(10_000);
        neverAutomatic.setAddRebuildRatio(10);
        neverAutomatic.setDeleteRebuildRatio(10);
        IndexDefinition flat = IndexDefinition.of("vectors", "vectors", "vector", 2,
                ru.nsu.fit.sberlab.vectorindex.core.Metric.COSINE, AlgorithmKind.FLAT, neverAutomatic);
        try (Fixture fixture = new Fixture(flat)) {
            fixture.source.owned.set(new int[]{0, 1});        // партиция 1 пустая
            fixture.source.put(10, 1, 0);
            fixture.rebuildAll();
            fixture.source.put(12, 0, 1);
            fixture.eventAndAwaitApplied(12, 1);               // маленькая дельта ниже порога
            fixture.builds.runAll();
            assertThat(fixture.builds.pending()).isZero();

            fixture.adapter.beginBarrier("b4", true, TimeUnit.SECONDS.toNanos(5));
            fixture.scans.runAll();
            NodeBarrierProgress scanned = fixture.adapter.barrierProgress("b4");
            assertThat(scanned.scannedPartitions).containsExactly(0, 1);
            assertThat(scanned.optimizedPartitions).isEmpty();
            assertThat(fixture.builds.pending()).as("builds are forced by the barrier").isPositive();

            fixture.builds.runAll();
            NodeBarrierProgress optimized = fixture.adapter.barrierProgress("b4");
            assertThat(optimized.optimizedPartitions).containsExactly(0, 1);
            assertThat(fixture.adapter.localStatus().deltaEntries).isZero();
            assertThat(fixture.adapter.localStatus().optimizationBuilt).isEqualTo(2);
            assertThat(fixture.adapter.searchLocal(new float[]{0, 1}, 1)).extracting(h -> h.key).containsExactly(12L);
        }
    }

    @Test
    void forcedReconcileIsNotLostWhileAnotherReconcileRuns() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.put(10, 1, 0);
            fixture.rebuildAll();
            long requestedBefore = fixture.adapter.runtime(0).status().repairRequested();
            FakeVectorSource.ScanBarrier running = fixture.source.blockNextOwnershipQuery();
            Future<?> periodic = fixture.scanWorker.submit(() -> fixture.adapter.forceReconcile(false));
            assertThat(running.finishedReading.await(3, TimeUnit.SECONDS)).isTrue();

            fixture.adapter.forceReconcile(true);              // сверка занята: запрос запоминается
            running.release.countDown();
            periodic.get(3, TimeUnit.SECONDS);

            assertThat(fixture.adapter.runtime(0).status().repairRequested())
                    .as("the full reconcile ran after the busy one").isEqualTo(requestedBefore + 1);
            assertThat(fixture.scans.pending()).isEqualTo(1);
        }
    }

    @Test
    void barrierFailsExplicitlyWhenAppliesDoNotFinish() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.put(10, 1, 0);
            fixture.rebuildAll();
            long requestedBefore = fixture.adapter.runtime(0).status().repairRequested();
            fixture.blockApplier();
            fixture.adapter.onKeyChanged(10L);

            assertThatThrownBy(() -> fixture.adapter.beginBarrier("b5", false, TimeUnit.MILLISECONDS.toNanos(100)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("did not finish");
            assertThat(fixture.adapter.runtime(0).status().repairRequested()).as("no tickets issued")
                    .isEqualTo(requestedBefore);
            assertThat(fixture.adapter.barrierProgress("b5").unknownBarrier).isTrue();
        }
    }

    @Test
    void truncateSuspensionClosesRuntimesAndIgnoresEventsUntilResume() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.source.put(10, 1, 0);
            fixture.rebuildAll();
            var before = fixture.adapter.runtime(0);

            fixture.adapter.suspendForTruncate(60_000, TimeUnit.SECONDS.toNanos(5));
            assertThat(before.isClosed()).isTrue();
            assertThat(fixture.adapter.runtime(0)).isNull();
            fixture.source.values.clear();                     // кэш очищен
            fixture.adapter.onKeyChanged(10L);                 // события на паузе игнорируются
            fixture.adapter.forceReconcile(false);             // сверка на паузе не создаёт runtime
            assertThat(fixture.adapter.runtime(0)).isNull();

            fixture.adapter.resumeIndexing();
            fixture.scans.runAll();
            assertThat(fixture.adapter.runtime(0)).isNotSameAs(before);
            assertThat(fixture.adapter.runtime(0).phase()).isEqualTo(Phase.READY);
            assertThat(fixture.adapter.searchLocal(new float[]{1, 0}, 10)).isEmpty();
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
            this(IndexDefinition.fromLegacy("vectors", 2, IndexType.BRUTE_FORCE_INDEX, new JVectorProperties()));
        }

        Fixture(IndexDefinition definition) {
            BuildService buildService = new BuildService(builds, delayed,
                    Backoff.exponential(Duration.ofSeconds(1), Duration.ofSeconds(8)),
                    RuntimeHarness.BUILD_POOL, RuntimeHarness.BUILD_POOL);
            NodeResources resources = new NodeResources(applier, scans, Runnable::run, delayed, null, buildService,
                    List.of(applier::close));
            adapter = new IgniteIndexAdapter(definition, source, resources, clock, new NullLogger());
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

        void awaitSearch(float[] query, IndexHit expected) {
            await().atMost(ofSeconds(3)).untilAsserted(() ->
                    assertThat(adapter.searchLocal(query, 10))
                            .extracting(h -> h.key, h -> h.distance)
                            .containsExactly(tuple(expected.key, expected.distance)));
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
