package ru.nsu.fit.sberlab.vectorindex.node.index;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexHit;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeSearchResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.PartitionFailure;
import ru.nsu.fit.sberlab.vectorindex.core.Budget;
import ru.nsu.fit.sberlab.vectorindex.core.Hit;
import ru.nsu.fit.sberlab.vectorindex.core.LocalSearchResult;
import ru.nsu.fit.sberlab.vectorindex.core.PartitionRuntime;
import ru.nsu.fit.sberlab.vectorindex.core.Phase;
import ru.nsu.fit.sberlab.vectorindex.core.ReadView;
import ru.nsu.fit.sberlab.vectorindex.core.SearchParams;
import ru.nsu.fit.sberlab.vectorindex.core.algorithm.AlgorithmSearchParams;

/**
 * Поиск узла по назначенным партициям. Покрытие — ровно те партиции, вид которых был открыт
 * в фазе READY и поиск по которым завершился в бюджете; фаза берётся из того же вида, что и
 * результаты, поэтому поздняя публикация не попадает в покрытие задним числом.
 */
final class LocalSearch {

    private LocalSearch() {
    }

    private record Outcome(int partition, List<Hit<Object>> hits, boolean knownGap, PartitionFailure.Reason failure) {
        static Outcome failed(int partition, PartitionFailure.Reason reason) {
            return new Outcome(partition, List.of(), false, reason);
        }
    }

    static NodeSearchResult search(Map<Integer, PartitionRuntime<Object>> runtimes, int[] partitions, float[] query,
                                   int k, AlgorithmSearchParams params, boolean exact, Budget budget, Executor pool,
                                   String nodeId, long topologyVersion) {
        int n = partitions.length;
        AtomicReferenceArray<Outcome> outcomes = new AtomicReferenceArray<>(n);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            int index = i;
            int partition = partitions[i];
            PartitionRuntime<Object> runtime = runtimes.get(partition);
            if (runtime == null) {
                outcomes.set(index, Outcome.failed(partition, PartitionFailure.Reason.NO_RUNTIME));
                done.countDown();
                continue;
            }
            Runnable task = () -> {
                Outcome outcome;
                try {
                    outcome = searchPartition(runtime, partition, query, k, params, exact, budget);
                } catch (RuntimeException e) {
                    outcome = Outcome.failed(partition, PartitionFailure.Reason.ERROR);
                }
                outcomes.compareAndSet(index, null, outcome);
                done.countDown();
            };
            try {
                pool.execute(task);
            } catch (RejectedExecutionException overloaded) {
                outcomes.set(index, Outcome.failed(partition, PartitionFailure.Reason.OVERLOADED));
                done.countDown();
            }
        }
        awaitWithin(done, budget);

        NodeSearchResult result = new NodeSearchResult();
        result.nodeId = nodeId;
        result.topologyVersion = topologyVersion;
        List<Integer> searched = new ArrayList<>();
        List<Integer> gaps = new ArrayList<>();
        List<Hit<Object>> hits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // Не успевшие в бюджет задачи больше не могут записать результат.
            outcomes.compareAndSet(i, null, Outcome.failed(partitions[i], PartitionFailure.Reason.BUDGET));
            Outcome outcome = outcomes.get(i);
            if (outcome.failure() != null) {
                result.failedPartitions.add(new PartitionFailure(outcome.partition(), outcome.failure(), nodeId));
                continue;
            }
            searched.add(outcome.partition());
            if (outcome.knownGap()) {
                gaps.add(outcome.partition());
            }
            hits.addAll(outcome.hits());
        }
        hits.sort(Comparator.comparingDouble(Hit::distance));
        List<IndexHit> top = new ArrayList<>(Math.min(k, hits.size()));
        for (Hit<Object> hit : hits) {
            if (top.size() >= k) {
                break;
            }
            top.add(new IndexHit(hit.key(), hit.distance()));
        }
        result.hits = top;
        result.searchedPartitions = searched.stream().mapToInt(Integer::intValue).toArray();
        result.knownGapPartitions = gaps.stream().mapToInt(Integer::intValue).toArray();
        return result;
    }

    private static Outcome searchPartition(PartitionRuntime<Object> runtime, int partition, float[] query, int k,
                                           AlgorithmSearchParams params, boolean exact, Budget budget) {
        Optional<ReadView<Object>> opened = runtime.openView();
        if (opened.isEmpty()) {
            return Outcome.failed(partition, PartitionFailure.Reason.CLOSED);
        }
        try (ReadView<Object> view = opened.get()) {
            if (view.phase() != Phase.READY) {
                return Outcome.failed(partition, PartitionFailure.Reason.LOADING);
            }
            if (budget.exhausted()) {
                return Outcome.failed(partition, PartitionFailure.Reason.BUDGET);
            }
            LocalSearchResult<Object> found = view.search(query, new SearchParams(k, exact, params, budget));
            if (!found.complete()) {
                return Outcome.failed(partition, PartitionFailure.Reason.BUDGET);
            }
            return new Outcome(partition, found.hits(), view.status().knownGap(), null);
        }
    }

    private static void awaitWithin(CountDownLatch done, Budget budget) {
        try {
            if (budget.bounded()) {
                done.await(budget.remainingNanos(), TimeUnit.NANOSECONDS);
            } else {
                done.await();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
