package ru.nsu.fit.sberlab.vectorindex.node.service;

import java.util.Collection;
import java.util.Map;
import java.util.TreeSet;

import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexNodeStatus;
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
