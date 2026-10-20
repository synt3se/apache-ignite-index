package ru.nsu.fit.sberlab.vectorindex.core;

/** Монотонные часы одной JVM. Абсолютное значение нельзя передавать между машинами. */
@FunctionalInterface
public interface NanoClock {

    NanoClock SYSTEM = System::nanoTime;

    long nanoTime();
}
