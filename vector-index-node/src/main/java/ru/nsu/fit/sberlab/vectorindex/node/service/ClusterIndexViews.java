package ru.nsu.fit.sberlab.vectorindex.node.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexBarrier;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexNodeStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeBarrierProgress;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexOperationResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexOperationResult.NodeOutcome;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexOperationResult.Outcome;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexStatus;
import ru.nsu.fit.sberlab.vectorindex.node.search.SearchTopology;

/** Сборка кластерных представлений из ответов узлов; без Ignite, чтобы проверять правила тестами. */
final class ClusterIndexViews {

    private ClusterIndexViews() {
    }

    /**
     * Готовность по назначенным владельцам. Партиция готова, только если её назначенный владелец
     * ответил, индекс у него загружен с тем же отпечатком и партиция в фазе READY.
     *
     * @param statuses узел → его статус; узел без ответа отсутствует в карте
     */
    static IndexStatus status(String indexId, String fingerprint, SearchTopology.Snapshot snapshot,
                              Collection<String> serverNodes, Map<String, IndexNodeStatus> statuses) {
        IndexStatus status = new IndexStatus();
        status.indexId = indexId;
        status.fingerprint = fingerprint;
        status.topologyVersion = snapshot.version();
        status.expectedPartitions = snapshot.partitions();
        for (String node : serverNodes) {
            IndexNodeStatus st = statuses.get(node);
            if (st == null) {
                status.unknownNodes.add(node);
                continue;
            }
            status.nodes.add(st);
            if (st.state == IndexNodeStatus.State.UNLOADED) {
                status.unloadedNodes.add(node);
            } else if (st.state == IndexNodeStatus.State.UNKNOWN_INDEX) {
                status.unknownIndexNodes.add(node);
            } else if (!compatible(st, fingerprint)) {
                status.mismatchedNodes.add(node);
            } else {
                add(status, st);
            }
        }

        TreeSet<Integer> ready = new TreeSet<>();
        TreeSet<Integer> loading = new TreeSet<>();
        TreeSet<Integer> missing = new TreeSet<>();
        TreeSet<Integer> repair = new TreeSet<>();
        Map<Integer, String> owners = new java.util.HashMap<>();
        snapshot.assignment().forEach((node, parts) -> {
            for (int p : parts) {
                owners.put(p, node);
            }
        });
        for (int p = 0; p < snapshot.partitions(); p++) {
            String owner = owners.get(p);
            IndexNodeStatus st = owner == null ? null : statuses.get(owner);
            if (st == null || !compatible(st, fingerprint)) {
                missing.add(p);
                continue;
            }
            if (contains(st.repairPendingPartitions, p)) {
                repair.add(p);
            }
            if (contains(st.readyPartitions, p)) {
                ready.add(p);
            } else if (contains(st.loadingPartitions, p)) {
                loading.add(p);
            } else {
                missing.add(p);
            }
        }
        status.readyPartitions = toArray(ready);
        status.loadingPartitions = toArray(loading);
        status.missingPartitions = toArray(missing);
        status.repairPendingPartitions = toArray(repair);

        status.searchable = status.expectedPartitions > 0 && ready.size() == status.expectedPartitions;
        status.knownGap = !repair.isEmpty();
        status.observedReady = status.searchable && !status.knownGap
                && status.pendingApplies == 0 && status.scansInProgress == 0
                && status.unknownNodes.isEmpty() && status.unloadedNodes.isEmpty()
                && status.unknownIndexNodes.isEmpty() && status.mismatchedNodes.isEmpty();
        return status;
    }

    /**
     * Барьер загрузки по кластеру. {@code COMPLETE} — каждая ожидаемая партиция у назначенного
     * владельца прошла скан после тикета (и оптимизацию, если запрошена), применений и сканов нет,
     * все серверы ответили. Смена топологии, замена runtime, партиция у владельца без барьера или
     * узел, потерявший барьер, — {@code RESTART}; отказ узла (индекс неизвестен, выгружен, другое
     * определение, применения не завершились) — {@code FAILED}.
     */
    static IndexBarrier barrier(String indexId, String fingerprint, String barrierId, SearchTopology.Snapshot current,
                                Collection<String> serverNodes, Map<String, NodeBarrierProgress> progress,
                                boolean begin) {
        IndexBarrier barrier = new IndexBarrier();
        barrier.barrierId = barrierId;
        barrier.indexId = indexId;
        barrier.fingerprint = fingerprint;
        barrier.topologyVersion = current.version();
        barrier.expectedPartitions = current.partitions();
        List<String> errors = new ArrayList<>();
        List<String> restarts = new ArrayList<>();
        TreeSet<Integer> failedBuilds = new TreeSet<>();
        for (String node : serverNodes) {
            NodeBarrierProgress p = progress.get(node);
            if (p == null) {
                barrier.unknownNodes.add(node);
                continue;
            }
            barrier.nodes.add(p);
            if (p.error != null) {
                errors.add(node + ": " + p.error);
                continue;
            }
            if (p.unknownBarrier) {
                restarts.add(node + " does not know the barrier");
            } else if (p.topologyVersion != current.version()) {
                restarts.add(node + " issued tickets at topology " + p.topologyVersion + ", now " + current.version());
            } else if (p.invalidatedPartitions.length > 0 || p.foreignPartitions.length > 0) {
                restarts.add(node + " changed runtimes of partitions " + Arrays.toString(p.invalidatedPartitions)
                        + " / " + Arrays.toString(p.foreignPartitions));
            }
            barrier.optimize |= p.optimize;
            barrier.liveVectors += p.liveVectors;
            barrier.pendingApplies += p.pendingApplies;
            barrier.scansInProgress += p.scansInProgress;
            for (int partition : p.failedBuildPartitions) {
                failedBuilds.add(partition);
            }
        }
        Map<Integer, String> owners = new HashMap<>();
        current.assignment().forEach((node, parts) -> {
            for (int partition : parts) {
                owners.put(partition, node);
            }
        });
        TreeSet<Integer> waiting = new TreeSet<>();
        for (int partition = 0; partition < current.partitions(); partition++) {
            String owner = owners.get(partition);
            NodeBarrierProgress p = owner == null ? null : progress.get(owner);
            if (p == null || p.error != null || p.unknownBarrier) {
                waiting.add(partition);
                continue;
            }
            if (!contains(p.partitions, partition)) {
                restarts.add("partition " + partition + " is owned by " + owner + " without a barrier ticket");
                waiting.add(partition);
                continue;
            }
            boolean scanned = contains(p.scannedPartitions, partition);
            boolean optimized = contains(p.optimizedPartitions, partition);
            barrier.scannedPartitions += scanned ? 1 : 0;
            barrier.optimizedPartitions += optimized ? 1 : 0;
            if (!scanned || (p.optimize && !optimized)) {
                waiting.add(partition);
            }
        }
        barrier.waitingPartitions = toArray(waiting);
        barrier.failedBuildPartitions = toArray(failedBuilds);
        if (!errors.isEmpty()) {
            barrier.state = IndexBarrier.State.FAILED;
            barrier.message = String.join("; ", errors);
        } else if (!restarts.isEmpty()) {
            barrier.state = IndexBarrier.State.RESTART;
            barrier.message = String.join("; ", restarts);
        } else if (waiting.isEmpty() && barrier.unknownNodes.isEmpty() && barrier.pendingApplies == 0
                && barrier.scansInProgress == 0 && current.partitions() > 0) {
            barrier.state = IndexBarrier.State.COMPLETE;
        } else {
            barrier.state = begin ? IndexBarrier.State.STARTED : IndexBarrier.State.IN_PROGRESS;
        }
        return barrier;
    }

    /**
     * Результат операции по узлам; подтверждена, только если ответили все серверы топологии и
     * каждый с допустимым исходом.
     */
    static IndexOperationResult operation(String indexId, String operation, Collection<String> serverNodes,
                                          Map<String, NodeOutcome> outcomes, Collection<Outcome> accepted) {
        IndexOperationResult result = new IndexOperationResult();
        result.indexId = indexId;
        result.operation = operation;
        result.expectedNodes = serverNodes.size();
        boolean confirmed = !serverNodes.isEmpty();
        for (String node : serverNodes) {
            NodeOutcome outcome = outcomes.get(node);
            if (outcome == null) {
                outcome = new NodeOutcome(node, Outcome.NO_RESPONSE, null);
            }
            result.nodes.add(outcome);
            confirmed &= accepted.contains(outcome.outcome);
        }
        result.confirmed = confirmed;
        return result;
    }

    private static boolean compatible(IndexNodeStatus st, String fingerprint) {
        return st.state == IndexNodeStatus.State.LOADED && fingerprint != null && fingerprint.equals(st.fingerprint);
    }

    private static void add(IndexStatus status, IndexNodeStatus st) {
        status.maxRepairPendingAgeMs = Math.max(status.maxRepairPendingAgeMs, st.maxRepairPendingAgeMs);
        status.optimizationNotBuilt += st.optimizationNotBuilt;
        status.optimizationBuilding += st.optimizationBuilding;
        status.optimizationBuilt += st.optimizationBuilt;
        status.optimizationFailed += st.optimizationFailed;
        status.liveVectors += st.liveVectors;
        status.deltaEntries += st.deltaEntries;
        status.frozenLayers += st.frozenLayers;
        status.pendingApplies += st.pendingApplies;
        status.scansInProgress += st.scansInProgress;
        status.rejectedVectorsTotal += st.rejectedVectorsTotal;
        status.wrongTypeValuesTotal += st.wrongTypeValuesTotal;
        status.unsupportedKeysTotal += st.unsupportedKeysTotal;
        status.applyErrorsTotal += st.applyErrorsTotal;
        status.applierOverflowsTotal += st.applierOverflowsTotal;
    }

    private static boolean contains(int[] values, int value) {
        if (values == null) {
            return false;
        }
        for (int v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    private static int[] toArray(TreeSet<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).toArray();
    }
}
