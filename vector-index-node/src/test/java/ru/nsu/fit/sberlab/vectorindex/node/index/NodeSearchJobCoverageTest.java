package ru.nsu.fit.sberlab.vectorindex.node.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.ignite.Ignite;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeSearchResult;
import ru.nsu.fit.sberlab.vectorindex.common.indextype.IndexType;
import ru.nsu.fit.sberlab.vectorindex.node.compute.nodework.NodeSearchJob;

class NodeSearchJobCoverageTest {
    @Test
    @DisplayName("A node without a local index manager reports no covered partitions")
    void noManagerMeansNoCoverage() {
        try (MockedStatic<NodeIndexContext> context = mockStatic(NodeIndexContext.class)) {
            context.when(NodeIndexContext::manager).thenReturn(null);

            NodeSearchResult result = new NodeSearchJob(new float[]{1, 0}, 3).call();

            assertThat(result.results).isEmpty();
            assertThat(result.activePartitions).isZero();
        }
    }

    @Test
    @Tag("known-issue")
    @DisplayName("К4: a partition activated after its search snapshot must not count as searched")
    void coverageDescribesSearchedPartitionsRatherThanLaterActivation() throws Exception {
        PartitionIndexManager manager = new PartitionIndexManager(mock(Ignite.class), "vectors", 2,
                IndexType.BRUTE_FORCE_INDEX, new JVectorProperties());
        PartitionState partition = mock(PartitionState.class);
        AtomicBoolean published = new AtomicBoolean();
        when(partition.indexOrNull()).thenAnswer(invocation -> {
            // The search observes the old snapshot: there is no usable index yet.
            // Publication happens immediately afterwards, before the separate stats pass.
            published.set(true);
            return null;
        });
        when(partition.isActive()).thenAnswer(invocation -> published.get());
        partitionsOf(manager).put(0, partition);

        try (MockedStatic<NodeIndexContext> context = mockStatic(NodeIndexContext.class)) {
            context.when(NodeIndexContext::manager).thenReturn(manager);

            NodeSearchResult result = new NodeSearchJob(new float[]{1, 0}, 3).call();

            assertThat(result.results).isEmpty();
            assertThat(result.activePartitions)
                    .as("the only partition had no index when this request visited it")
                    .isZero();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, PartitionState> partitionsOf(PartitionIndexManager manager) throws Exception {
        Field field = PartitionIndexManager.class.getDeclaredField("partitions");
        field.setAccessible(true);
        return (Map<Integer, PartitionState>) field.get(manager);
    }
}
