package ru.nsu.fit.sberlab.vectorindex.node.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexHit;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeSearchResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.PartitionFailure;
import ru.nsu.fit.sberlab.vectorindex.core.Budget;

/**
 * Координатор поиска. Назначение (ожидаемые партиции и владельцы) фиксируется в начале запроса;
 * каждый узел ищет только по назначенным ему партициям и получает остаток общего бюджета.
 * Покрытие — объединение партиций, о которых назначенный узел сообщил как об обработанных:
 * одна партиция не считается дважды по построению, а сообщения о неназначенных
 * партициях игнорируются. Повтор ключа — нарушение назначения: он учитывается, а не
 * «разрешается» минимальным расстоянием как выбор версии.
 */
public final class SearchCoordinator {

    private final SearchTopology topology;
    private final NodeTransport transport;
    private final Awaiter awaiter;

    public SearchCoordinator(SearchTopology topology, NodeTransport transport, Awaiter awaiter) {
        this.topology = Objects.requireNonNull(topology, "topology");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.awaiter = Objects.requireNonNull(awaiter, "awaiter");
    }

    public DistributedSearchResult search(NodeQuery query, Budget budget) {
        SearchTopology.Snapshot snapshot = topology.snapshot();
        List<PartitionFailure> failed = new ArrayList<>();
        Set<Integer> assigned = new HashSet<>();
        snapshot.assignment().values().forEach(parts -> {
            for (int p : parts) assigned.add(p);
        });
        for (int p = 0; p < snapshot.partitions(); p++) {
            if (!assigned.contains(p)) {
                failed.add(new PartitionFailure(p, PartitionFailure.Reason.NO_OWNER, null));
            }
        }

        // Scatter: только узлы с назначенными партициями.
        Map<String, CompletableFuture<NodeSearchResult>> futures = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> entry : snapshot.assignment().entrySet()) {
            if (entry.getValue().length == 0) {
                continue;
            }
            NodeSearchRequest request = new NodeSearchRequest(query, entry.getValue().clone(), budget.remainingNanos());
            try {
                futures.put(entry.getKey(), transport.search(entry.getKey(), request));
            } catch (RuntimeException sendFailed) {
                futures.put(entry.getKey(), CompletableFuture.failedFuture(sendFailed));
            }
        }

        // Gather в пределах остатка бюджета.
        TreeSet<Integer> searched = new TreeSet<>();
        TreeSet<Integer> gaps = new TreeSet<>();
        Map<Object, Double> best = new HashMap<>();
        int responded = 0;
        int duplicates = 0;
        boolean timedOut = false;
        for (Map.Entry<String, CompletableFuture<NodeSearchResult>> entry : futures.entrySet()) {
            String node = entry.getKey();
            int[] parts = snapshot.assignment().get(node);
            long remaining = budget.remainingNanos();
            if (remaining <= 0) {
                timedOut = true;
                entry.getValue().cancel(true);
                failAll(failed, parts, PartitionFailure.Reason.NODE_TIMEOUT, node);
                continue;
            }
            NodeSearchResult result;
            try {
                result = awaiter.await(entry.getValue(), remaining);
            } catch (TimeoutException timeout) {
                timedOut = true;
                entry.getValue().cancel(true);
                failAll(failed, parts, PartitionFailure.Reason.NODE_TIMEOUT, node);
                continue;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                timedOut = true;
                failAll(failed, parts, PartitionFailure.Reason.NODE_TIMEOUT, node);
                continue;
            } catch (ExecutionException | RuntimeException nodeFailed) {
                failAll(failed, parts, PartitionFailure.Reason.NODE_FAILED, node);
                continue;
            }
            if (result == null) {
                failAll(failed, parts, PartitionFailure.Reason.NODE_NO_RESPONSE, node);
                continue;
            }
            responded++;
            Set<Integer> mine = new HashSet<>();
            for (int p : parts) mine.add(p);
            Set<Integer> reported = new HashSet<>();
            for (int p : nullSafe(result.searchedPartitions)) {
                if (mine.contains(p) && searched.add(p)) {
                    reported.add(p);
                }
            }
            for (int p : nullSafe(result.knownGapPartitions)) {
                if (reported.contains(p)) {
                    gaps.add(p);
                }
            }
            if (result.failedPartitions != null) {
                for (PartitionFailure failure : result.failedPartitions) {
                    if (mine.contains(failure.partition) && reported.add(failure.partition)) {
                        failed.add(new PartitionFailure(failure.partition, failure.reason, node));
                    }
                }
            }
            for (int p : parts) {
                if (!reported.contains(p)) {
                    failed.add(new PartitionFailure(p, PartitionFailure.Reason.NOT_REPORTED, node));
                }
            }
            if (result.hits != null) {
                for (IndexHit hit : result.hits) {
                    Double previous = best.get(hit.key);
                    if (previous != null) {
                        duplicates++;
                        if (hit.distance < previous) {
                            best.put(hit.key, hit.distance);
                        }
                    } else {
                        best.put(hit.key, hit.distance);
                    }
                }
            }
        }
        if (budget.exhausted()) {
            timedOut = true;
        }

        List<IndexHit> ordered = new ArrayList<>(best.size());
        best.forEach((key, distance) -> ordered.add(new IndexHit(key, distance)));
        ordered.sort(IndexHit.ORDER);
        failed.sort((a, b) -> Integer.compare(a.partition, b.partition));
        return new DistributedSearchResult(ordered, snapshot.partitions(), toArray(searched), failed, toArray(gaps),
                timedOut, responded, futures.size(), duplicates);
    }

    private static void failAll(List<PartitionFailure> failed, int[] parts, PartitionFailure.Reason reason, String node) {
        for (int p : parts) {
            failed.add(new PartitionFailure(p, reason, node));
        }
    }

    private static int[] nullSafe(int[] values) {
        return values == null ? new int[0] : values;
    }

    private static int[] toArray(Set<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).toArray();
    }
}
