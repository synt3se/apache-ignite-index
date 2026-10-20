package ru.nsu.fit.sberlab.vectorindex.core;

import java.util.Objects;

/**
 * Бюджет выполнения запроса внутри одной JVM. Между узлами передаётся только остаток
 * ({@link #remainingNanos()}), из которого получатель строит свой {@code Budget}.
 * Просроченный или отменённый запрос не начинает следующий дорогой этап.
 */
public final class Budget {

    private static final long UNBOUNDED = Long.MAX_VALUE / 4;

    private final NanoClock clock;
    private final long deadlineNanos;
    private volatile boolean cancelled;

    private Budget(NanoClock clock, long deadlineNanos) {
        this.clock = clock;
        this.deadlineNanos = deadlineNanos;
    }

    /** Без ограничения по времени; отмена через {@link #cancel()} работает. */
    public static Budget unlimited() {
        return new Budget(null, 0);
    }

    /** Остаток {@code nanos} от текущего момента {@code clock}; неположительный — уже исчерпан. */
    public static Budget remaining(NanoClock clock, long nanos) {
        Objects.requireNonNull(clock, "clock");
        if (nanos >= UNBOUNDED) {
            return new Budget(null, 0);
        }
        return new Budget(clock, clock.nanoTime() + nanos);
    }

    public boolean exhausted() {
        return cancelled || (clock != null && clock.nanoTime() - deadlineNanos >= 0);
    }

    public long remainingNanos() {
        if (cancelled) {
            return 0;
        }
        if (clock == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, deadlineNanos - clock.nanoTime());
    }

    public boolean bounded() {
        return clock != null;
    }

    public void cancel() {
        cancelled = true;
    }
}
