package ru.nsu.fit.sberlab.vectorindex.core;

/** Когда упаковывать дельту в новую базу. Решение — по физическим размерам слоёв. */
@FunctionalInterface
public interface MaintenancePolicy {

    /** Только явный {@link PartitionRuntime#capture()} (тесты, ручное управление). */
    MaintenancePolicy MANUAL = stats -> false;

    boolean shouldOptimize(LayerStats stats);

    record LayerStats(int baseSize, boolean baseOptimized, long deltaEntries, long deltaTombstones,
                      int frozenLayers) {
        public long deltaLive() {
            return deltaEntries - deltaTombstones;
        }
    }

    /**
     * Пороги прежней схемы JVector: дельта ≥ max(minDelta, ⌈baseSize·addRatio⌉) или
     * tombstone ≥ baseSize·deleteRatio. Непостроенная непустая база и оставшиеся после
     * ошибки frozen-слои оптимизируются сразу.
     */
    static MaintenancePolicy thresholds(int minDelta, double addRatio, double deleteRatio) {
        return stats -> {
            if (stats.frozenLayers() > 0) {
                return true;
            }
            if (!stats.baseOptimized() && stats.baseSize() > 0) {
                return true;
            }
            if (stats.deltaEntries() == 0) {
                return false;
            }
            long addThreshold = Math.max(minDelta, (long) Math.ceil(stats.baseSize() * addRatio));
            if (stats.deltaLive() >= Math.max(1, addThreshold)) {
                return true;
            }
            return stats.baseSize() > 0 && stats.deltaTombstones() >= stats.baseSize() * deleteRatio;
        };
    }
}
