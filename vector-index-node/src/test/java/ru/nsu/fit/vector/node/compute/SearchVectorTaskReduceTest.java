package ru.nsu.fit.vector.node.compute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.apache.ignite.compute.ComputeJobResult;
import org.junit.jupiter.api.Test;
import ru.nsu.fit.vector.common.ScoredVector;

class SearchVectorTaskReduceTest {

    private static ComputeJobResult resultWith(List<ScoredVector> data) {
        ComputeJobResult r = mock(ComputeJobResult.class);
        doReturn(data).when(r).getData();
        return r;
    }

    @Test
    void reduceDeduplicatesByIdKeepingMinDistance() {
        // id=1 приходит с двух узлов (окно ребаланса) с разной дистанцией
        ComputeJobResult n1 = resultWith(List.of(new ScoredVector(1L, 0.5), new ScoredVector(2L, 1.0)));
        ComputeJobResult n2 = resultWith(List.of(new ScoredVector(1L, 0.2), new ScoredVector(3L, 2.0)));

        SearchVectorTask task = new SearchVectorTask();
        task.map(List.of(), new SearchVectorTask.Arg(new float[]{1f}, 10, null)); // выставляет searchCount
        List<ScoredVector> out = task.reduce(List.of(n1, n2));

        assertThat(out).extracting(ScoredVector::id).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(out).extracting(ScoredVector::id).doesNotHaveDuplicates();

        ScoredVector one = out.stream().filter(s -> s.id() == 1L).findFirst().orElseThrow();
        assertThat(one.distance()).isEqualTo(0.2); // осталась минимальная
    }
}