package ru.nsu.fit.sberlab.vectorindex.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexNodeStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexOperationResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexOperationResult.NodeOutcome;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexOperationResult.Outcome;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchRequest;
import ru.nsu.fit.sberlab.vectorindex.common.indextype.IndexType;
import ru.nsu.fit.sberlab.vectorindex.node.index.IndexDefinition;
import ru.nsu.fit.sberlab.vectorindex.node.search.SearchTopology;

/** Кластерная готовность и результаты операций по ответам узлов; проверка запроса поиска. */
class ClusterIndexViewsTest {

    private static final String FP = "fp";
    private final Map<String, int[]> assignment = new LinkedHashMap<>();
    private final Map<String, IndexNodeStatus> statuses = new LinkedHashMap<>();

    @Test
    void readyOnlyWhenEveryExpectedPartitionIsReadyAtItsAssignedOwner() {
        assignment.put("a", new int[]{0, 1});
        assignment.put("b", new int[]{2, 3});
        statuses.put("a", loaded("a", new int[]{0, 1}, new int[0]));
        statuses.put("b", loaded("b", new int[]{2, 3}, new int[0]));

        IndexStatus status = status();

        assertThat(status.readyPartitions).containsExactly(0, 1, 2, 3);
        assertThat(status.searchable).isTrue();
        assertThat(status.observedReady).isTrue();
    }

    @Test
    void readyAtANodeThatIsNotTheAssignedOwnerDoesNotCount() {
        assignment.put("a", new int[]{0, 1});
        assignment.put("b", new int[]{2, 3});
        statuses.put("a", loaded("a", new int[]{0, 1, 2}, new int[0])); // 2 уже назначена b
        statuses.put("b", loaded("b", new int[]{3}, new int[]{2}));

        IndexStatus status = status();

        assertThat(status.readyPartitions).containsExactly(0, 1, 3);
        assertThat(status.loadingPartitions).containsExactly(2);
        assertThat(status.searchable).isFalse();
    }

    @Test
    void knownGapRunningAppliesScansAndUnknownNodesAreNotObservedReadiness() {
        assignment.put("a", new int[]{0, 1});
        IndexNodeStatus a = loaded("a", new int[]{0, 1}, new int[0]);
        statuses.put("a", a);
        assertThat(status().observedReady).isTrue();

        a.repairPendingPartitions = new int[]{1};
        assertThat(status().knownGap).isTrue();
        assertThat(status().searchable).as("searchable, but with a known gap").isTrue();
        assertThat(status().observedReady).isFalse();
        a.repairPendingPartitions = new int[0];

        a.pendingApplies = 1; // последнее изменение ещё применяется, очередь может быть пуста
        assertThat(status().observedReady).isFalse();
        a.pendingApplies = 0;

        a.scansInProgress = 1;
        assertThat(status().observedReady).isFalse();
        a.scansInProgress = 0;

        IndexStatus withSilentNode = ClusterIndexViews.status("images", FP, snapshot(), List.of("a", "c"), statuses);
        assertThat(withSilentNode.unknownNodes).containsExactly("c");
        assertThat(withSilentNode.observedReady).isFalse();
    }

    @Test
    void ownersWithAnotherDefinitionOrUnloadedIndexLeaveTheirPartitionsMissing() {
        assignment.put("a", new int[]{0});
        assignment.put("b", new int[]{1});
        assignment.put("c", new int[]{2});
        statuses.put("a", loaded("a", new int[]{0}, new int[0]));
        IndexNodeStatus other = loaded("b", new int[]{1}, new int[0]);
        other.fingerprint = "other";
        statuses.put("b", other);
        IndexNodeStatus unloaded = new IndexNodeStatus();
        unloaded.state = IndexNodeStatus.State.UNLOADED;
        unloaded.fingerprint = FP;
        statuses.put("c", unloaded);

        IndexStatus status = ClusterIndexViews.status("images", FP, new SearchTopology.Snapshot(3, assignment, 7),
                List.of("a", "b", "c"), statuses);

        assertThat(status.readyPartitions).containsExactly(0);
        assertThat(status.missingPartitions).containsExactly(1, 2);
        assertThat(status.mismatchedNodes).containsExactly("b");
        assertThat(status.unloadedNodes).containsExactly("c");
        assertThat(status.searchable).isFalse();
        assertThat(status.topologyVersion).isEqualTo(7);
    }

    @Test
    void operationIsConfirmedOnlyWhenEveryServerAnsweredAcceptably() {
        EnumSet<Outcome> unload = EnumSet.of(Outcome.DONE, Outcome.ALREADY_UNLOADED);
        Map<String, NodeOutcome> outcomes = new LinkedHashMap<>();
        outcomes.put("a", new NodeOutcome("a", Outcome.DONE, null));
        outcomes.put("b", new NodeOutcome("b", Outcome.ALREADY_UNLOADED, null));

        IndexOperationResult all = ClusterIndexViews.operation("images", "UNLOAD", List.of("a", "b"), outcomes, unload);
        assertThat(all.confirmed).isTrue();

        IndexOperationResult missing = ClusterIndexViews.operation("images", "UNLOAD", List.of("a", "b", "c"),
                outcomes, unload);
        assertThat(missing.confirmed).as("an unavailable node cannot confirm the unload").isFalse();
        assertThat(missing.nodes).extracting(n -> n.nodeId, n -> n.outcome).contains(
                org.assertj.core.groups.Tuple.tuple("c", Outcome.NO_RESPONSE));

        outcomes.put("b", new NodeOutcome("b", Outcome.UNKNOWN_INDEX, null));
        assertThat(ClusterIndexViews.operation("images", "UNLOAD", List.of("a", "b"), outcomes, unload).confirmed)
                .isFalse();
    }

    @Test
    void searchRequestIsValidatedAgainstTheCoordinatorDefinition() {
        IndexDefinition definition = IndexDefinition.legacy(2, IndexType.BRUTE_FORCE_INDEX, null);

        assertThat(SearchAggregatorServiceImpl.query(definition, request(new float[]{1, 0}, 3)).k()).isEqualTo(3);
        assertThatIllegalArgumentException().isThrownBy(() ->
                SearchAggregatorServiceImpl.query(definition, request(new float[]{1, 0, 0}, 3)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                SearchAggregatorServiceImpl.query(definition, request(new float[]{Float.NaN, 0}, 3)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                SearchAggregatorServiceImpl.query(definition, request(new float[]{1, 0}, 0)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                SearchAggregatorServiceImpl.query(definition, request(null, 3)));
        VectorSearchRequest withParams = request(new float[]{1, 0}, 3);
        withParams.params.put("ef", 10);
        assertThatIllegalArgumentException().as("FLAT has no search parameters")
                .isThrownBy(() -> SearchAggregatorServiceImpl.query(definition, withParams));
    }

    private IndexStatus status() {
        return ClusterIndexViews.status("images", FP, snapshot(), List.copyOf(statuses.keySet()), statuses);
    }

    private SearchTopology.Snapshot snapshot() {
        int partitions = assignment.values().stream().mapToInt(parts -> parts.length).sum();
        return new SearchTopology.Snapshot(partitions, assignment, 1);
    }

    private static IndexNodeStatus loaded(String node, int[] ready, int[] loading) {
        IndexNodeStatus status = new IndexNodeStatus();
        status.nodeId = node;
        status.state = IndexNodeStatus.State.LOADED;
        status.fingerprint = FP;
        status.readyPartitions = ready;
        status.loadingPartitions = loading;
        return status;
    }

    private static VectorSearchRequest request(float[] vector, int k) {
        return new VectorSearchRequest("vectors", vector, k);
    }
}
