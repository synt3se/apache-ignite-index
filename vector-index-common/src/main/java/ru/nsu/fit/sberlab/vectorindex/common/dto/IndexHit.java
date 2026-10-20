package ru.nsu.fit.sberlab.vectorindex.common.dto;

import java.io.Serializable;
import java.util.Comparator;

/** Попадание поиска по индексу: исходный ключ источника ({@link KeyType}) и расстояние. */
public class IndexHit implements Serializable {

    private static final long serialVersionUID = 1L;

    /** По расстоянию; при равенстве — по типу и значению ключа (детерминированный порядок). */
    public static final Comparator<IndexHit> ORDER = Comparator.<IndexHit>comparingDouble(hit -> hit.distance)
            .thenComparing(IndexHit::compareKeys);

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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareKeys(IndexHit a, IndexHit b) {
        KeyType ta = a.keyType();
        KeyType tb = b.keyType();
        if (ta != tb || ta == null) {
            return Integer.compare(ta == null ? -1 : ta.ordinal(), tb == null ? -1 : tb.ordinal());
        }
        return ((Comparable) a.key).compareTo(b.key);
    }

    @Override
    public String toString() {
        return key + ":" + distance;
    }
}
