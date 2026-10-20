package ru.nsu.fit.sberlab.vectorindex.testkit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Executor, задачи которого выполняет тест: детерминированное расписание без потоков и
 * произвольных задержек. Может имитировать переполнение ({@link #rejectNext}).
 */
public final class ManualExecutor extends AbstractExecutorService {

    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private boolean shutdown;
    private int rejections;

    /** Следующие {@code count} отправок будут отклонены, как при переполненной очереди. */
    public synchronized void rejectNext(int count) {
        rejections = count;
    }

    public synchronized int pending() {
        return tasks.size();
    }

    public void runNext() {
        Runnable task;
        synchronized (this) {
            task = tasks.remove();
        }
        task.run();
    }

    /** Выполняет задачи, пока очередь не опустеет; ограничено, чтобы сломанный повтор не завесил тест. */
    public void runAll() {
        for (int i = 0; i < 1_000; i++) {
            synchronized (this) {
                if (tasks.isEmpty()) {
                    return;
                }
            }
            runNext();
        }
        throw new AssertionError("tasks did not settle after 1000 runs");
    }

    @Override
    public synchronized void execute(Runnable command) {
        if (shutdown) {
            throw new RejectedExecutionException("executor is shut down");
        }
        if (rejections > 0) {
            rejections--;
            throw new RejectedExecutionException("simulated overflow");
        }
        tasks.add(command);
    }

    @Override
    public synchronized void shutdown() {
        shutdown = true;
    }

    @Override
    public synchronized List<Runnable> shutdownNow() {
        shutdown = true;
        List<Runnable> remaining = new ArrayList<>(tasks);
        tasks.clear();
        return remaining;
    }

    @Override
    public synchronized boolean isShutdown() {
        return shutdown;
    }

    @Override
    public synchronized boolean isTerminated() {
        return shutdown && tasks.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return isTerminated();
    }
}
