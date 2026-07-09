package ru.nsu.fit.vector.node.index;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class StripedApplier implements AutoCloseable {

    private final ThreadPoolExecutor[] stripes;

    StripedApplier(int stripeCount, int queueCapacityPerStripe) {
        stripes = new ThreadPoolExecutor[Math.max(1, stripeCount)];
        AtomicInteger seq = new AtomicInteger();
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacityPerStripe),
                    r -> {
                        Thread t = new Thread(r, "index-applier-" + seq.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
        }
    }

    /** @return false если полоса переполнена (задача НЕ принята). */
    boolean submit(int partition, Runnable task) {
        ThreadPoolExecutor stripe = stripes[Math.floorMod(partition, stripes.length)];
        try {
            stripe.execute(task);
            return true;
        } catch (RejectedExecutionException overflow) {
            return false;
        }
    }

    long backlog() {
        long total = 0;
        for (ThreadPoolExecutor s : stripes) total += s.getQueue().size();
        return total;
    }

    @Override
    public void close() {
        for (ThreadPoolExecutor s : stripes) s.shutdownNow();
    }
}