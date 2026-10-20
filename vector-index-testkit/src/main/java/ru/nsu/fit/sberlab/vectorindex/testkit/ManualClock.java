package ru.nsu.fit.sberlab.vectorindex.testkit;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import ru.nsu.fit.sberlab.vectorindex.core.NanoClock;

/** Часы, которые двигает тест. */
public final class ManualClock implements NanoClock {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    @Override
    public long nanoTime() {
        return now.get();
    }

    public void advance(Duration duration) {
        now.addAndGet(duration.toNanos());
    }

    public void advanceNanos(long nanos) {
        now.addAndGet(nanos);
    }
}
