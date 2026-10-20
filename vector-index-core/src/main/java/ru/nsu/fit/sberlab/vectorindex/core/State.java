package ru.nsu.fit.sberlab.vectorindex.core;

import java.util.List;

/**
 * Единое неизменяемое состояние runtime: {@code base + frozen + active} и служебные поля
 * протокола. Публикуется одной volatile-ссылкой; читатели работают без замков.
 *
 * <p>Правило разрешения ключа одно для всех операций (поиск, маски, get, дедупликация,
 * {@code liveCount}, материализация сборки): верхняя запись слоёв (active, затем frozen
 * от новых к старым) действует, если её ревизия больше ревизии базы; иначе действует база.
 */
final class State {

    final Phase phase;
    final FrozenGroup group;
    final BaseSegment base;
    final List<DeltaLayer> frozen;   // от старых к новым
    final DeltaLayer active;
    final long liveCount;
    final BuildToken<?> outstanding;
    final String lastBuildFailure;
    final ScanSession<?> scan;
    final WriteLog scanWrites;

    private State(Phase phase, FrozenGroup group, DeltaLayer active, long liveCount,
                  BuildToken<?> outstanding, String lastBuildFailure, ScanSession<?> scan, WriteLog scanWrites) {
        this.phase = phase;
        this.group = group;
        this.base = group.base;
        this.frozen = group.frozen;
        this.active = active;
        this.liveCount = liveCount;
        this.outstanding = outstanding;
        this.lastBuildFailure = lastBuildFailure;
        this.scan = scan;
        this.scanWrites = scanWrites;
    }

    static State initial() {
        return new State(Phase.LOADING, new FrozenGroup(BaseSegment.empty(), List.of()), DeltaLayer.EMPTY,
                0, null, null, null, null);
    }

    /** Действующий вектор ключа или {@code null}, если ключа в проекции нет. */
    float[] resolve(Object key) {
        Entry top = active.get(key);
        for (int i = frozen.size() - 1; top == null && i >= 0; i--) {
            top = frozen.get(i).get(key);
        }
        // Ревизии убывают сверху вниз: если верхняя запись не новее базы, нижние тоже.
        if (top != null && top.revision() > base.revision) {
            return top.vector();
        }
        int ordinal = base.ordinalOf(key);
        return ordinal >= 0 ? base.vectors[ordinal] : null;
    }

    long deltaEntries() {
        long total = active.size();
        for (DeltaLayer layer : frozen) {
            total += layer.size();
        }
        return total;
    }

    long deltaTombstones() {
        long total = active.tombstones;
        for (DeltaLayer layer : frozen) {
            total += layer.tombstones;
        }
        return total;
    }

    OptimizationState optimization() {
        if (outstanding != null) {
            return OptimizationState.BUILDING;
        }
        if (lastBuildFailure != null) {
            return OptimizationState.FAILED;
        }
        return base.optimized() ? OptimizationState.BUILT : OptimizationState.NOT_BUILT;
    }

    MaintenancePolicy.LayerStats layerStats() {
        return new MaintenancePolicy.LayerStats(base.size(), base.optimized(), deltaEntries(),
                deltaTombstones(), frozen.size());
    }

    State withActive(DeltaLayer nextActive, long nextLive, WriteLog nextWrites) {
        return new State(phase, group, nextActive, nextLive, outstanding, lastBuildFailure, scan, nextWrites);
    }

    State captured(List<DeltaLayer> nextFrozen, BuildToken<?> token) {
        return new State(phase, group.sameAs(base, nextFrozen), DeltaLayer.EMPTY, liveCount, token,
                lastBuildFailure, scan, scanWrites);
    }

    /** Публикация сборки: логическое содержимое прежнее, поэтому {@code liveCount} не меняется. */
    State published(BaseSegment built) {
        return new State(phase, new FrozenGroup(built, List.of()), active, liveCount, null, null, scan, scanWrites);
    }

    State withoutOutstanding() {
        return new State(phase, group, active, liveCount, null, lastBuildFailure, scan, scanWrites);
    }

    State buildFailed(String failure) {
        return new State(phase, group, active, liveCount, null, failure, scan, scanWrites);
    }

    State withScan(ScanSession<?> session, WriteLog writes) {
        return new State(phase, group, active, liveCount, outstanding, lastBuildFailure, session, writes);
    }

    /**
     * Публикация скана: база скана с ревизией {@code startRevision}; записи active не новее
     * неё перестают действовать. Frozen отбрасываются: во время скана capture запрещён, значит
     * все их записи не новее старта скана. Незавершённая сборка останется outstanding и будет
     * отклонена при публикации (база сменилась).
     */
    State scanPublished(BaseSegment staging, long nextLive) {
        return new State(Phase.READY, new FrozenGroup(staging, List.of()), active, nextLive, outstanding,
                lastBuildFailure, null, null);
    }

    State closed() {
        return new State(Phase.CLOSED, group, DeltaLayer.EMPTY, 0, null, lastBuildFailure, null, null);
    }
}
