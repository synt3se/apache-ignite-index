package ru.nsu.fit.sberlab.vectorindex.testkit;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import ru.nsu.fit.sberlab.vectorindex.core.maintenance.DelayedExecutor;

/** Отложенные задачи по {@link ManualClock}: выполняются явно через {@link #runDue()}. */
public final class ManualDelayedExecutor implements DelayedExecutor {

    private final ManualClock clock;
    private final List<Scheduled> scheduled = new ArrayList<>();

    public ManualDelayedExecutor(ManualClock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized void schedule(Runnable task, long delayNanos) {
        scheduled.add(new Scheduled(task, clock.nanoTime() + delayNanos, delayNanos));
    }

    public synchronized int pending() {
        return scheduled.size();
    }

    /** Задержки запланированных задач — для проверки backoff. */
    public synchronized List<Long> delays() {
        List<Long> delays = new ArrayList<>();
        for (Scheduled s : scheduled) {
            delays.add(s.delayNanos);
        }
        return delays;
    }

    /** Выполняет задачи, срок которых наступил по часам. */
    public void runDue() {
        List<Runnable> due = new ArrayList<>();
        synchronized (this) {
            Iterator<Scheduled> it = scheduled.iterator();
            while (it.hasNext()) {
                Scheduled s = it.next();
                if (s.dueNanos - clock.nanoTime() <= 0) {
                    due.add(s.task);
                    it.remove();
                }
            }
        }
        due.forEach(Runnable::run);
    }

    private record Scheduled(Runnable task, long dueNanos, long delayNanos) {
    }
}
