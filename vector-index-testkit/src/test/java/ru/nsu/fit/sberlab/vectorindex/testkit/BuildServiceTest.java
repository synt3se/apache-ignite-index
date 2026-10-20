package ru.nsu.fit.sberlab.vectorindex.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ru.nsu.fit.sberlab.vectorindex.core.MaintenancePolicy;
import ru.nsu.fit.sberlab.vectorindex.core.Metric;
import ru.nsu.fit.sberlab.vectorindex.core.OptimizationState;
import ru.nsu.fit.sberlab.vectorindex.core.PartitionRuntime;
import ru.nsu.fit.sberlab.vectorindex.core.RuntimeConfig;
import ru.nsu.fit.sberlab.vectorindex.core.ScanSession;
import ru.nsu.fit.sberlab.vectorindex.core.VectorSpec;
import ru.nsu.fit.sberlab.vectorindex.core.flat.FlatAlgorithm;
import ru.nsu.fit.sberlab.vectorindex.core.maintenance.Backoff;
import ru.nsu.fit.sberlab.vectorindex.core.maintenance.BuildService;

/** Планирование сборок: управляемый executor, часы и отложенные повторы — без потоков и sleep. */
@Timeout(30)
class BuildServiceTest {
    private static final VectorSpec SPEC = new VectorSpec(2, Metric.SQUARED_L2);

    private final ManualClock clock = new ManualClock();
    private final ManualExecutor pool = new ManualExecutor();
    private final ManualDelayedExecutor retries = new ManualDelayedExecutor(clock);
    private final BuildService service = new BuildService(pool, retries,
            Backoff.exponential(Duration.ofSeconds(1), Duration.ofSeconds(8)),
            RuntimeHarness.BUILD_POOL, RuntimeHarness.BUILD_POOL);
    private final TrackingAlgorithm algorithm = new TrackingAlgorithm(FlatAlgorithm.INSTANCE);

    private PartitionRuntime<Long> runtime(MaintenancePolicy policy) {
        return PartitionRuntime.create(RuntimeConfig.of("svc", 0, SPEC, algorithm)
                .withPolicy(policy, service::requestOptimization).withClock(clock));
    }

    private static void publishScan(PartitionRuntime<Long> rt, Map<Long, float[]> records) {
        ScanSession<Long> scan = rt.beginScan();
        records.forEach(scan::offer);
        rt.publishScan(scan);
    }

    @Test
    void scanPublicationRequestsOneBuildAndDuplicateRequestsAreCoalesced() {
        PartitionRuntime<Long> rt = runtime(MaintenancePolicy.thresholds(100, 0.1, 0.2));
        publishScan(rt, Map.of(1L, new float[]{1, 1}, 2L, new float[]{2, 2}));
        service.requestOptimization(rt);
        service.requestOptimization(rt);

        assertThat(pool.pending()).isEqualTo(1);
        pool.runAll();
        assertThat(rt.status().optimization()).isEqualTo(OptimizationState.BUILT);
        assertThat(service.published()).isEqualTo(1);
        assertThat(rt.needsOptimization()).isFalse();
    }

    @Test
    void thresholdCrossedDuringBuildTriggersAnotherBuildAfterPublication() {
        PartitionRuntime<Long> rt = runtime(MaintenancePolicy.thresholds(2, 0, 10));
        publishScan(rt, Map.of(1L, new float[]{1, 1}));
        pool.runAll();
        rt.upsert(2L, new float[]{2, 2});
        rt.upsert(3L, new float[]{3, 3});            // порог: запрос сборки
        assertThat(pool.pending()).isEqualTo(1);

        // Записи во время сборки входят в active и не могут начать вторую сборку (токен занят);
        // после публикации сервис сам перепроверяет порог.
        algorithm.onNextBuild(() -> {
            rt.upsert(4L, new float[]{4, 4});
            rt.upsert(5L, new float[]{5, 5});
        });
        pool.runNext();
        assertThat(rt.status().deltaEntries()).isEqualTo(2);
        assertThat(pool.pending()).as("follow-up build requested after publication").isEqualTo(1);
        pool.runAll();
        assertThat(rt.status().deltaEntries()).isZero();
        assertThat(rt.status().liveCount()).isEqualTo(5);
    }

    @Test
    void failedBuildIsRetriedWithBackoffAndTokenIsReleased() {
        PartitionRuntime<Long> rt = runtime(MaintenancePolicy.thresholds(100, 0.1, 0.2));
        algorithm.failNextBuilds(2);
        publishScan(rt, Map.of(1L, new float[]{1, 1}));
        pool.runAll();

        assertThat(rt.status().optimization()).isEqualTo(OptimizationState.FAILED);
        assertThat(retries.delays()).containsExactly(Duration.ofSeconds(1).toNanos());
        retries.runDue();
        assertThat(pool.pending()).as("not due yet").isZero();
        clock.advance(Duration.ofSeconds(1));
        retries.runDue();
        pool.runAll();
        assertThat(retries.delays()).containsExactly(Duration.ofSeconds(2).toNanos());

        clock.advance(Duration.ofSeconds(2));
        retries.runDue();
        pool.runAll();
        assertThat(rt.status().optimization()).isEqualTo(OptimizationState.BUILT);
        assertThat(rt.status().buildFailures()).isEqualTo(2);
        assertThat(service.failed()).isEqualTo(2);
        assertThat(algorithm.openStructures()).isEqualTo(1);
    }

    @Test
    void rejectedSubmissionCapturesNothingAndIsRetried() {
        PartitionRuntime<Long> rt = runtime(MaintenancePolicy.thresholds(100, 0.1, 0.2));
        pool.rejectNext(1);
        publishScan(rt, Map.of(1L, new float[]{1, 1}));

        assertThat(service.rejectedSubmissions()).isEqualTo(1);
        assertThat(rt.status().optimization()).as("no token captured").isEqualTo(OptimizationState.NOT_BUILT);
        clock.advance(Duration.ofSeconds(1));
        retries.runDue();
        pool.runAll();
        assertThat(rt.status().optimization()).isEqualTo(OptimizationState.BUILT);
    }

    @Test
    void runtimeClosedWhileQueuedLeavesNothingBehind() {
        PartitionRuntime<Long> rt = runtime(MaintenancePolicy.thresholds(100, 0.1, 0.2));
        publishScan(rt, Map.of(1L, new float[]{1, 1}));
        rt.close();
        pool.runAll();

        assertThat(algorithm.built()).isEmpty();
        assertThat(retries.pending()).isZero();
    }

    @Test
    void errorFromBuildIsRecordedAndNotSuppressed() {
        PartitionRuntime<Long> rt = runtime(MaintenancePolicy.thresholds(100, 0.1, 0.2));
        publishScan(rt, Map.of(1L, new float[]{1, 1}));
        algorithm.failNextBuildWith(new OutOfMemoryError("simulated"));

        assertThatThrownBy(pool::runNext).isInstanceOf(OutOfMemoryError.class);
        assertThat(rt.status().optimization()).isEqualTo(OptimizationState.FAILED);
        assertThat(rt.capture()).as("BUILDING released").isPresent();
    }

    @Test
    void closedServiceIgnoresRequests() {
        PartitionRuntime<Long> rt = runtime(MaintenancePolicy.MANUAL);
        publishScan(rt, Map.of(1L, new float[]{1, 1}));
        service.close();
        service.requestOptimization(rt);

        assertThat(pool.pending()).isZero();
    }
}
