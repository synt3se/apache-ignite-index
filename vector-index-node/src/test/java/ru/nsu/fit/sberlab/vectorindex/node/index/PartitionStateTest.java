package ru.nsu.fit.sberlab.vectorindex.node.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import ru.nsu.fit.sberlab.vectorindex.common.ScoredVector;

class PartitionStateTest {

    @Test
    void initialPartitionBecomesSearchableOnlyAfterACompleteBuild() {
        PartitionState state = new PartitionState(7);
        assertThat(state.isActive()).isFalse();
        assertThat(state.indexOrNull()).isNull();

        boolean retry = state.rebuild(partition -> {
            assertThat(partition).isEqualTo(7);
            assertThat(state.isActive()).isFalse();
            return index(Map.of(1L, new float[]{1, 0}));
        }, (target, key) -> {
            throw new AssertionError("There were no cache changes to replay");
        });

        assertThat(retry).isFalse();
        assertThat(state.isActive()).isTrue();
        assertThat(state.indexOrNull().search(new float[]{1, 0}, 1))
                .extracting(ScoredVector::id, ScoredVector::distance)
                .containsExactly(tuple(1L, 0.0));
    }

    @Test
    void cacheChangesDuringScanAreReplayedUsingTheLatestValueBeforePublication() {
        PartitionState state = new PartitionState(0);
        Map<Long, float[]> cache = new HashMap<>(Map.of(
                1L, new float[]{1, 0}, 2L, new float[]{0, 1}));
        AtomicInteger replayed = new AtomicInteger();
        KeyApplier applyCurrentValue = (target, key) -> {
            replayed.incrementAndGet();
            float[] current = cache.get(key);
            if (current == null) target.delete(key);
            else target.add(key, current);
        };

        boolean retry = state.rebuild(partition -> {
            PartitionVectorIndex snapshot = index(cache);
            cache.put(1L, new float[]{-1, 0});
            state.onKeyChanged(1L, applyCurrentValue);
            cache.put(1L, new float[]{0, -1});
            state.onKeyChanged(1L, applyCurrentValue);
            cache.remove(2L);
            state.onKeyChanged(2L, applyCurrentValue);
            cache.put(3L, new float[]{1, 0});
            state.onKeyChanged(3L, applyCurrentValue);
            assertThat(replayed).hasValue(0);
            return snapshot;
        }, applyCurrentValue);

        assertThat(retry).isFalse();
        assertThat(replayed).hasValue(3);
        assertThat(state.indexOrNull().search(new float[]{0, -1}, 10))
                .extracting(ScoredVector::id, ScoredVector::distance)
                .containsExactly(tuple(1L, 0.0), tuple(3L, 1.0));
    }

    @Test
    void previousSnapshotRemainsSearchableUntilReplacementIsPublished() {
        PartitionState state = new PartitionState(0);
        state.rebuild(partition -> index(Map.of(1L, new float[]{1, 0})),
                (target, key) -> {});
        PartitionVectorIndex previous = state.indexOrNull();

        state.rebuild(partition -> {
            assertThat(state.indexOrNull()).isSameAs(previous);
            assertThat(state.indexOrNull().search(new float[]{1, 0}, 10))
                    .extracting(ScoredVector::id, ScoredVector::distance)
                    .containsExactly(tuple(1L, 0.0));
            return index(Map.of(2L, new float[]{0, 1}));
        }, (target, key) -> {});

        assertThat(state.indexOrNull()).isNotSameAs(previous);
        assertThat(state.indexOrNull().search(new float[]{0, 1}, 10))
                .extracting(ScoredVector::id, ScoredVector::distance)
                .containsExactly(tuple(2L, 0.0));
    }

    @Test
    void activePartitionAppliesChangesWithoutWaitingForAnotherScan() {
        PartitionState state = new PartitionState(0);
        state.rebuild(partition -> index(Map.of(1L, new float[]{1, 0})),
                (target, key) -> {});

        state.onKeyChanged(1L, (target, key) -> target.add(key, new float[]{0, 1}));
        assertThat(state.indexOrNull().search(new float[]{0, 1}, 1))
                .extracting(ScoredVector::id, ScoredVector::distance)
                .containsExactly(tuple(1L, 0.0));

        state.onKeyChanged(1L, PartitionVectorIndex::delete);
        assertThat(state.indexOrNull().search(new float[]{0, 1}, 1)).isEmpty();
    }

    private static PartitionVectorIndex index(Map<Long, float[]> vectors) {
        PartitionVectorIndex index = new BruteForcePartitionIndex();
        index.build(vectors);
        return index;
    }
}
