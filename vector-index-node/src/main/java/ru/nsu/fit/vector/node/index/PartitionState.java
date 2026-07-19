package ru.nsu.fit.vector.node.index;

import java.util.LinkedHashSet;

final class PartitionState {

    enum State { REBUILDING, ACTIVE }

    private static final int MAX_PENDING = 100_000;

    final int partition;
    private final Object lock = new Object();
    private final LinkedHashSet<Long> pending = new LinkedHashSet<>();
    private boolean overflow;

    private volatile State state = State.REBUILDING;
    private volatile PartitionVectorIndex index;

    PartitionState(int partition) {
        this.partition = partition;
    }

    boolean isActive() {
        return state == State.ACTIVE && index != null;
    }

    PartitionVectorIndex indexOrNull() {
        return index;
    }

    void onKeyChanged(long key, KeyApplier applier) {
        synchronized (lock) {
            if (state == State.ACTIVE) {
                applier.apply(index, key);
            } else if (pending.size() < MAX_PENDING) {
                pending.add(key);
            } else {
                overflow = true;
            }
        }
    }

    /** @return true если во время ребилда переполнился буфер - нужен ещё проход. */
    boolean rebuild(IndexBuilder builder, KeyApplier applier) {
        synchronized (lock) {
            state = State.REBUILDING;
            pending.clear();
            overflow = false;
        }
        PartitionVectorIndex fresh = builder.buildFor(partition); // долго, ВНЕ замка
        synchronized (lock) {
            for (Long key : pending) applier.apply(fresh, key);
            pending.clear();
            index = fresh;                 // атомарная подмена ссылки
            state = State.ACTIVE;
            boolean again = overflow;
            overflow = false;
            return again;
        }
    }
}

@FunctionalInterface
interface KeyApplier {
    void apply(PartitionVectorIndex target, long key);
}

@FunctionalInterface
interface IndexBuilder {
    PartitionVectorIndex buildFor(int partition);
}