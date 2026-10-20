package ru.nsu.fit.sberlab.vectorindex.core;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Скан источника для одного runtime. Одновременно действует одна сессия: новый
 * {@link PartitionRuntime#beginScan()} вытесняет прежнюю. Публикация и отмена сверяют
 * идентичность сессии, поэтому поздний {@link #close()} вытесненной сессии ничего не трогает.
 * Заполняется одним потоком скана.
 */
public final class ScanSession<K> implements AutoCloseable {

    final PartitionRuntime<K> runtime;
    final long startRevision;
    final long repairTicket;
    private final LinkedHashMap<Object, float[]> records = new LinkedHashMap<>();
    private final AtomicBoolean finished;

    ScanSession(PartitionRuntime<K> runtime, long startRevision, long repairTicket, boolean dead) {
        this.runtime = runtime;
        this.startRevision = startRevision;
        this.repairTicket = repairTicket;
        this.finished = new AtomicBoolean(dead);
    }

    /**
     * Добавляет прочитанную запись источника (копия; для COSINE — нормализованная).
     * Повтор ключа — последнее значение выигрывает. Неверный вектор исключается и учитывается.
     *
     * @throws IllegalArgumentException для {@code vector == null}: отсутствие вектора решает адаптер
     */
    public ApplyResult.Status offer(K key, float[] vector) {
        Objects.requireNonNull(key, "key");
        if (finished.get()) {
            return ApplyResult.Status.CLOSED;
        }
        Ingest ingest = runtime.spec().ingest(vector);
        if (ingest instanceof Ingest.Accepted accepted) {
            records.put(key, accepted.vector());
            return ApplyResult.Status.APPLIED;
        }
        records.remove(key);
        runtime.countRejected(((Ingest.Rejected) ingest).reason());
        return ApplyResult.Status.REJECTED;
    }

    public long startRevision() {
        return startRevision;
    }

    public int size() {
        return records.size();
    }

    /** Действующая ли ещё сессия (не опубликована, не отменена, не вытеснена). */
    public boolean active() {
        return !finished.get();
    }

    /** Отмена, если сессия ещё действующая; иначе ничего. */
    @Override
    public void close() {
        runtime.abortScan(this);
    }

    /** @return {@code true} ровно один раз — для того, кто завершает сессию */
    boolean finish() {
        return finished.compareAndSet(false, true);
    }

    BaseSegment toStagingBase() {
        Object[] keys = records.keySet().toArray();
        float[][] vectors = records.values().toArray(new float[0][]);
        records.clear();
        return BaseSegment.of(keys, vectors, startRevision, null);
    }
}
