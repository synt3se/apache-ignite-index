package ru.nsu.fit.sberlab.vectorindex.node.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexHit;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeSearchResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.PartitionFailure;
import ru.nsu.fit.sberlab.vectorindex.core.Budget;
import ru.nsu.fit.sberlab.vectorindex.core.Metric;
import ru.nsu.fit.sberlab.vectorindex.core.PartitionRuntime;
import ru.nsu.fit.sberlab.vectorindex.core.RepairReason;
import ru.nsu.fit.sberlab.vectorindex.core.RuntimeConfig;
import ru.nsu.fit.sberlab.vectorindex.core.ScanSession;
import ru.nsu.fit.sberlab.vectorindex.core.VectorSpec;
import ru.nsu.fit.sberlab.vectorindex.core.flat.FlatAlgorithm;

/** Покрытие узла — множество фактически обработанных партиций. Перенос {@code NodeSearchJobCoverageTest}. */
class LocalSearchTest {
    private static final VectorSpec SPEC = new VectorSpec(2, Metric.COSINE);

    private final Map<Integer, PartitionRuntime<Object>> runtimes = new ConcurrentHashMap<>();

    @Test
    @DisplayName("An assigned partition without a local runtime is reported, not counted")
    void noManagerMeansNoCoverage() {
        NodeSearchResult result = search(new int[]{0}, Runnable::run, Budget.unlimited());

        assertThat(result.hits).isEmpty();
        assertThat(result.searchedPartitions).isEmpty();
        assertThat(result.failedPartitions).extracting(f -> f.reason).containsExactly(PartitionFailure.Reason.NO_RUNTIME);
    }

    @Test
    @DisplayName("К4: a partition activated after its search snapshot must not count as searched")
    void coverageDescribesSearchedPartitionsRatherThanLaterActivation() {
        PartitionRuntime<Object> partition = runtime(0);
        // Поиск видит старое состояние (загрузка ещё не опубликована); публикация происходит
        // сразу после этого, до сборки ответа.
        Executor publishRightAfterTheSearch = task -> {
            task.run();
            publish(partition, Map.of(1L, new float[]{1, 0}));
        };

        NodeSearchResult result = search(new int[]{0}, publishRightAfterTheSearch, Budget.unlimited());

        assertThat(result.hits).isEmpty();
        assertThat(result.searchedPartitions.length)
                .as("the only partition had no index when this request visited it")
                .isZero();
        assertThat(result.failedPartitions).extracting(f -> f.reason).containsExactly(PartitionFailure.Reason.LOADING);
        assertThat(search(new int[]{0}, Runnable::run, Budget.unlimited()).searchedPartitions.length)
                .as("the next request sees the published view").isEqualTo(1);
    }

    @Test
    void searchedPartitionsAndReasonsAreReportedPerPartition() {
        publish(runtime(0), Map.of(1L, new float[]{1, 0}));
        PartitionRuntime<Object> gap = runtime(1);
        publish(gap, Map.of(2L, new float[]{0, 1}));
        gap.requestRepair(RepairReason.APPLIER_OVERFLOW);
        runtime(2).close();
        runtime(3); // загрузка

        NodeSearchResult result = search(new int[]{0, 1, 2, 3, 4}, Runnable::run, Budget.unlimited());

        assertThat(result.searchedPartitions).containsExactly(0, 1);
        assertThat(result.knownGapPartitions).containsExactly(1);
        assertThat(result.failedPartitions).extracting(f -> f.partition, f -> f.reason).containsExactly(
                org.assertj.core.groups.Tuple.tuple(2, PartitionFailure.Reason.CLOSED),
                org.assertj.core.groups.Tuple.tuple(3, PartitionFailure.Reason.LOADING),
                org.assertj.core.groups.Tuple.tuple(4, PartitionFailure.Reason.NO_RUNTIME));
        assertThat(result.hits).extracting(h -> h.key).containsExactly(1L, 2L);
    }

    @Test
    void overloadedPoolAndExhaustedBudgetAreNotCoverage() {
        publish(runtime(0), Map.of(1L, new float[]{1, 0}));
        publish(runtime(1), Map.of(2L, new float[]{0, 1}));
        Executor rejecting = task -> {
            throw new RejectedExecutionException("full");
        };
        assertThat(search(new int[]{0, 1}, rejecting, Budget.unlimited()).failedPartitions)
                .extracting(f -> f.reason).containsOnly(PartitionFailure.Reason.OVERLOADED);

        AtomicLong now = new AtomicLong();
        Budget budget = Budget.remaining(now::get, 5);
        now.set(5);
        NodeSearchResult late = search(new int[]{0, 1}, Runnable::run, budget);
        assertThat(late.searchedPartitions).isEmpty();
        assertThat(late.failedPartitions).extracting(f -> f.reason).containsOnly(PartitionFailure.Reason.BUDGET);
    }

    private NodeSearchResult search(int[] partitions, Executor pool, Budget budget) {
        return LocalSearch.search(runtimes, partitions, new float[]{1, 0}, 3, null, budget, pool, "node-a", 7);
    }

    private PartitionRuntime<Object> runtime(int partition) {
        PartitionRuntime<Object> runtime = PartitionRuntime.create(
                RuntimeConfig.of("vectors", partition, SPEC, FlatAlgorithm.INSTANCE));
        runtimes.put(partition, runtime);
        return runtime;
    }

    private static void publish(PartitionRuntime<Object> runtime, Map<Long, float[]> records) {
        ScanSession<Object> scan = runtime.beginScan();
        records.forEach(scan::offer);
        runtime.publishScan(scan);
    }
}
