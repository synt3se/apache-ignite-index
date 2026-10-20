package ru.nsu.fit.sberlab.vectorindex.core;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import ru.nsu.fit.sberlab.vectorindex.core.algorithm.FrozenVectorView;

/**
 * Непрозрачный снимок захвата сборки: связывает конкретный runtime с захваченными
 * {@code base} и {@code frozen} по идентичности объектов. Удерживает захваченную базу
 * до {@link #close()}; закрывать в любом исходе (публикация, ошибка, отмена) — идемпотентно.
 * {@link PartitionRuntime#publish} и {@link PartitionRuntime#buildFailed} закрывают токен сами.
 */
public final class BuildToken<K> implements AutoCloseable {

    final PartitionRuntime<K> runtime;
    final BaseSegment base;
    final List<DeltaLayer> frozen;
    final long captureRevision;
    final long capturedAtNanos;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile MaterializedInput input;
    private volatile long materializedAtNanos;

    BuildToken(PartitionRuntime<K> runtime, BaseSegment base, List<DeltaLayer> frozen,
               long captureRevision, long capturedAtNanos) {
        this.runtime = runtime;
        this.base = base;
        this.frozen = frozen;
        this.captureRevision = captureRevision;
        this.capturedAtNanos = capturedAtNanos;
    }

    /** Стабильный вход сборки; материализуется при первом вызове (в потоке сборки). */
    public FrozenVectorView input() {
        return materialized();
    }

    public long captureRevision() {
        return captureRevision;
    }

    public boolean closed() {
        return closed.get();
    }

    MaterializedInput materialized() {
        MaterializedInput result = input;
        if (result == null) {
            synchronized (this) {
                result = input;
                if (result == null) {
                    if (closed.get()) {
                        throw new IllegalStateException("build token is closed");
                    }
                    materializedAtNanos = runtime.clock().nanoTime();
                    result = MaterializedInput.of(base, frozen, runtime.spec());
                    input = result;
                }
            }
        }
        return result;
    }

    long materializedAtNanos() {
        return materializedAtNanos;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            base.release();
        }
    }
}
