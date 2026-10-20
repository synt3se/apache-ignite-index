package ru.nsu.fit.sberlab.vectorindex.core.maintenance;

import java.time.Duration;

/** Задержка перед повтором сборки после {@code attempt}-й подряд ошибки (с 1). */
@FunctionalInterface
public interface Backoff {

    long delayNanos(int attempt);

    static Backoff exponential(Duration initial, Duration max) {
        long first = initial.toNanos();
        long cap = max.toNanos();
        return attempt -> {
            int shift = Math.min(Math.max(attempt - 1, 0), 30);
            long delay = first << shift;
            return delay <= 0 || delay > cap ? cap : delay;
        };
    }
}
