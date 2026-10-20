package ru.nsu.fit.sberlab.vectorindex.common.dto;

import java.io.Serializable;

/** Попадание поиска по индексу: исходный ключ источника ({@link KeyType}) и расстояние. */
public class IndexHit implements Serializable {

    private static final long serialVersionUID = 1L;

    public Object key;
    public double distance;

    public IndexHit() {
    }

    public IndexHit(Object key, double distance) {
        this.key = key;
        this.distance = distance;
    }

    public KeyType keyType() {
        return KeyType.of(key);
    }

    @Override
    public String toString() {
        return key + ":" + distance;
    }
}
