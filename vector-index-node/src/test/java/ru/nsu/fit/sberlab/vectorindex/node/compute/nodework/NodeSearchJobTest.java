package ru.nsu.fit.sberlab.vectorindex.node.compute.nodework;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeSearchResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.PartitionFailure;
import ru.nsu.fit.sberlab.vectorindex.node.search.NodeQuery;
import ru.nsu.fit.sberlab.vectorindex.node.search.NodeSearchRequest;

class NodeSearchJobTest {

    @Test
    @DisplayName("A node without a local index reports its assigned partitions as not covered")
    void noManagerMeansNoCoverage() {
        NodeSearchResult result = new NodeSearchJob(new NodeSearchRequest(
                new NodeQuery("vectors", "fingerprint", new float[]{1, 0}, 3, false, java.util.Map.of()), new int[]{0, 5}, 1_000))
                .call();

        assertThat(result.hits).isEmpty();
        assertThat(result.searchedPartitions).isEmpty();
        assertThat(result.failedPartitions).extracting(f -> f.partition, f -> f.reason).containsExactly(
                org.assertj.core.groups.Tuple.tuple(0, PartitionFailure.Reason.NO_INDEX),
                org.assertj.core.groups.Tuple.tuple(5, PartitionFailure.Reason.NO_INDEX));
    }
}
