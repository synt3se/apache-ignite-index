package ru.nsu.fit.sberlab.vectorindex.node.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/** Источник в памяти: партиция = key mod 2; управляемые барьеры скана и сбои чтения. */
final class FakeVectorSource implements VectorSource {

    private static final float[] NO_VECTOR = new float[0];

    final Map<Object, float[]> values = new ConcurrentHashMap<>();
    final AtomicReference<int[]> owned = new AtomicReference<>(new int[]{0});
    final Set<Object> failingReads = ConcurrentHashMap.newKeySet();
    final AtomicLong topology = new AtomicLong(1);
    private final AtomicReference<ScanBarrier> nextScan = new AtomicReference<>();
    private final List<ScanBarrier> barriers = new ArrayList<>();

    void put(long key, float... vector) {
        values.put(key, vector);
    }

    void putWithoutVector(long key) {
        values.put(key, NO_VECTOR);
    }

    void remove(long key) {
        values.remove(key);
    }

    /** Следующий скан остановится после последней записи, пока тест его не отпустит. */
    ScanBarrier blockNextScanAfterItsLastEntry() {
        ScanBarrier barrier = new ScanBarrier();
        synchronized (barriers) {
            barriers.add(barrier);
        }
        nextScan.set(barrier);
        return barrier;
    }

    void releaseAllBarriers() {
        synchronized (barriers) {
            barriers.forEach(barrier -> barrier.release.countDown());
        }
    }

    static int partition(Object key) {
        return (int) Math.floorMod((Long) key, 2L);
    }

    @Override
    public int[] ownedPrimaryPartitions() {
        return owned.get().clone();
    }

    @Override
    public boolean isLocalPrimary(int partition) {
        for (int p : owned.get()) {
            if (p == partition) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int partitionOf(Object key) {
        return partition(key);
    }

    @Override
    public float[] read(Object key) {
        if (failingReads.contains(key)) {
            throw new IllegalStateException("simulated read failure for " + key);
        }
        float[] vector = values.get(key);
        return vector == null || vector == NO_VECTOR ? null : vector.clone();
    }

    @Override
    public void scan(int partition, BiConsumer<Object, float[]> consumer) {
        ScanBarrier barrier = nextScan.getAndSet(null);
        for (Map.Entry<Object, float[]> entry : List.copyOf(values.entrySet())) {
            if (partition(entry.getKey()) == partition && entry.getValue() != NO_VECTOR) {
                consumer.accept(entry.getKey(), entry.getValue().clone());
            }
        }
        if (barrier != null) {
            barrier.finishedReading.countDown();
            await(barrier.release);
        }
    }

    @Override
    public long topologyVersion() {
        return topology.get();
    }

    static final class ScanBarrier {
        final CountDownLatch finishedReading = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for the test barrier");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted at test barrier", interrupted);
        }
    }
}
