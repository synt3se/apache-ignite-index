package ru.nsu.fit.sberlab.vectorindex.core;

import java.util.Map;

/**
 * Готовность — несколько признаков, а не один флаг (§7.5), и метрики runtime (§7.8).
 * Отсутствие известного пропуска ({@code repairPending == false}) не означает, что получены
 * все изменения источника: позиция источника в v1 неизвестна.
 *
 * @param liveCount           логическое число живых записей
 * @param deltaEntries        физический размер всех слоёв (включая перекрытые базой записи)
 * @param repairPendingAgeNanos приблизительный возраст ожидающего запроса сверки, 0 если нет
 * @param writerLockWaits     сколько раз писатель ждал замок (быстрый захват не считается)
 */
public record RuntimeStatus(
        String indexId,
        int partition,
        Phase phase,
        long liveCount,
        int baseSize,
        long baseRevision,
        long deltaEntries,
        long deltaTombstones,
        int frozenLayers,
        OptimizationState optimization,
        String lastBuildFailure,
        long buildsPublished,
        long buildFailures,
        long publishRejected,
        Map<RejectReason, Long> rejectedVectors,
        long writes,
        long dedupSkipped,
        long repairRequested,
        long repairCompleted,
        boolean repairPending,
        long repairPendingAgeNanos,
        boolean scanOpen,
        SourcePosition sourcePosition,
        long writerLockWaits,
        long writerLockWaitNanosTotal,
        long writerLockWaitNanosMax,
        long lastBuildDurationNanos,
        long lastPublishLatencyNanos,
        long annUnderfilledSearches,
        long exactFallbacks) {

    /** Подтверждённая позиция источника; в v1 источник её не предоставляет. */
    public enum SourcePosition { UNKNOWN }

    public RuntimeStatus {
        rejectedVectors = Map.copyOf(rejectedVectors);
    }

    public boolean searchable() {
        return phase == Phase.READY;
    }

    public long deltaLive() {
        return deltaEntries - deltaTombstones;
    }

    public long rejectedTotal() {
        long total = 0;
        for (long count : rejectedVectors.values()) {
            total += count;
        }
        return total;
    }

    /** Известный пробел относительно источника: результат поиска нельзя считать полным. */
    public boolean knownGap() {
        return repairPending;
    }
}
