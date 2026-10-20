package ru.nsu.fit.sberlab.vectorindex.core.maintenance;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Отложенный запуск (повторы после ошибок). Внедряется, чтобы тесты управляли временем. */
@FunctionalInterface
public interface DelayedExecutor {

    void schedule(Runnable task, long delayNanos);

    /** Обёртка планировщика узла; после его остановки задачи молча не принимаются. */
    static DelayedExecutor of(ScheduledExecutorService scheduler) {
        return (task, delayNanos) -> {
            try {
                scheduler.schedule(task, delayNanos, TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException shuttingDown) {
                // узел останавливается: повтор не нужен
            }
        };
    }
}
