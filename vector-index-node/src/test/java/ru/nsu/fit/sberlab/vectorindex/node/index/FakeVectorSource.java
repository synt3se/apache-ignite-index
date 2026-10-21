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

    final Map<Object, SourceValue> values = new ConcurrentHashMap<>();
    final AtomicReference<int[]> owned = new AtomicReference<>(new int[]{0});
    final Set<Object> failingReads = ConcurrentHashMap.newKeySet();
    final AtomicLong topology = new AtomicLong(1);
    private final AtomicReference<ScanBarrier> nextScan = new AtomicReference<>();
    private final Map<Object, ScanBarrier> blockedReads = new ConcurrentHashMap<>();
    private final Map<Object, ScanBarrier> staleReads = new ConcurrentHashMap<>();
    private final AtomicReference<ScanBarrier> nextOwnership = new AtomicReference<>();
    private final List<ScanBarrier> barriers = new ArrayList<>();

    void put(long key, float... vector) {
        putKey(key, vector);
    }

    /** Ключ любого типа (String, UUID, неподдерживаемые). */
    void putKey(Object key, float... vector) {
        values.put(key, new SourceValue.Vector(vector.clone()));
    }

    void putWithoutVector(long key) {
        values.put(key, SourceValue.NO_VECTOR);
    }

    void putWrongType(long key) {
        values.put(key, new SourceValue.WrongType("java.lang.String"));
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

    /** Следующее чтение ключа остановится внутри применения, пока тест его не отпустит. */
    ScanBarrier blockNextReadOf(Object key) {
        ScanBarrier barrier = new ScanBarrier();
        synchronized (barriers) {
            barriers.add(barrier);
        }
        blockedReads.put(key, barrier);
        return barrier;
    }

    /**
     * Следующее чтение ключа берёт текущее значение и только потом останавливается: обработчик
     * «прочитал старое значение и задержался», пока источник уже изменился.
     */
    ScanBarrier blockAfterReadOf(Object key) {
        ScanBarrier barrier = new ScanBarrier();
        synchronized (barriers) {
            barriers.add(barrier);
        }
        staleReads.put(key, barrier);
        return barrier;
    }

    /** Следующий запрос владения остановится внутри сверки, пока тест его не отпустит. */
    ScanBarrier blockNextOwnershipQuery() {
        ScanBarrier barrier = new ScanBarrier();
        synchronized (barriers) {
            barriers.add(barrier);
        }
        nextOwnership.set(barrier);
        return barrier;
    }

    void releaseAllBarriers() {
        synchronized (barriers) {
            barriers.forEach(barrier -> barrier.release.countDown());
        }
    }

    static int partition(Object key) {
        return key instanceof Long id ? (int) Math.floorMod(id, 2L) : Math.floorMod(key.hashCode(), 2);
    }

    @Override
    public int[] ownedPrimaryPartitions() {
        ScanBarrier barrier = nextOwnership.getAndSet(null);
        if (barrier != null) {
            barrier.finishedReading.countDown();
            await(barrier.release);
        }
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
    public SourceValue read(Object key) {
        if (failingReads.contains(key)) {
            throw new IllegalStateException("simulated read failure for " + key);
        }
        ScanBarrier barrier = blockedReads.remove(key);
        if (barrier != null) {
            barrier.finishedReading.countDown();
            await(barrier.release);
        }
        SourceValue current = copy(values.getOrDefault(key, SourceValue.ABSENT));
        ScanBarrier stale = staleReads.remove(key);
        if (stale != null) {
            stale.finishedReading.countDown();
            await(stale.release);
        }
        return current;
    }

    @Override
    public void scan(int partition, BiConsumer<Object, SourceValue> consumer) {
        ScanBarrier barrier = nextScan.getAndSet(null);
        for (Map.Entry<Object, SourceValue> entry : List.copyOf(values.entrySet())) {
            if (partition(entry.getKey()) == partition) {
                consumer.accept(entry.getKey(), copy(entry.getValue()));
            }
        }
        if (barrier != null) {
            barrier.finishedReading.countDown();
            await(barrier.release);
        }
    }

    private static SourceValue copy(SourceValue value) {
        return value instanceof SourceValue.Vector present ? new SourceValue.Vector(present.vector().clone()) : value;
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
