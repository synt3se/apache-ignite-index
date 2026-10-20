package ru.nsu.fit.sberlab.vectorindex.core.algorithm;

import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BooleanSupplier;

/**
 * Ресурсы сборки, выданные владельцем узла: явные пулы ForkJoin (никаких общих пулов JVM
 * и executor на каждый граф) и признак, что результат уже не будет опубликован.
 */
public interface BuildContext {

    ForkJoinPool simdPool();

    ForkJoinPool parallelPool();

    /** Сборку можно бросить: runtime закрыт, токен устарел или сервис сборок остановлен. */
    boolean cancelled();

    static BuildContext of(ForkJoinPool simdPool, ForkJoinPool parallelPool, BooleanSupplier cancelled) {
        Objects.requireNonNull(simdPool, "simdPool");
        Objects.requireNonNull(parallelPool, "parallelPool");
        Objects.requireNonNull(cancelled, "cancelled");
        return new BuildContext() {
            @Override public ForkJoinPool simdPool() { return simdPool; }
            @Override public ForkJoinPool parallelPool() { return parallelPool; }
            @Override public boolean cancelled() { return cancelled.getAsBoolean(); }
        };
    }

    static BuildContext of(ForkJoinPool pool) {
        return of(pool, pool, () -> false);
    }
}
