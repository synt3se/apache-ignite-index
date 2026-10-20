package ru.nsu.fit.sberlab.vectorindex.core;

/** Найденный ключ источника и расстояние по контракту {@link VectorSpec#distance}. */
public record Hit<K>(K key, double distance) {
}
