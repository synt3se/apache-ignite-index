package ru.nsu.fit.sberlab.vectorindex.core;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import ru.nsu.fit.sberlab.vectorindex.core.algorithm.SearchStructure;

/**
 * Неизменяемая база: {@code ordinal ↔ key}, векторы и (необязательно) структура алгоритма.
 * {@link #revision} — ревизия runtime, до которой база перекрывает записи слоёв.
 * Счётчик ссылок: одну держит состояние, по одной — каждый открытый вид и токен сборки;
 * структура закрывается, когда отпущена последняя ссылка.
 */
final class BaseSegment {

    final Object[] keys;
    final float[][] vectors;
    final long revision;
    final SearchStructure structure;
    private final HashMap<Object, Integer> ordinals;
    private final AtomicInteger references = new AtomicInteger(1);

    private BaseSegment(Object[] keys, float[][] vectors, HashMap<Object, Integer> ordinals,
                        long revision, SearchStructure structure) {
        this.keys = keys;
        this.vectors = vectors;
        this.ordinals = ordinals;
        this.revision = revision;
        this.structure = structure;
    }

    static BaseSegment empty() {
        return new BaseSegment(new Object[0], new float[0][], new HashMap<>(), 0, null);
    }

    /** O(n) — вызывать вне замка писателей. Ключи должны быть уникальны. */
    static BaseSegment of(Object[] keys, float[][] vectors, long revision, SearchStructure structure) {
        HashMap<Object, Integer> ordinals = new HashMap<>((int) (keys.length / 0.75f) + 1);
        for (int i = 0; i < keys.length; i++) {
            if (ordinals.put(keys[i], i) != null) {
                throw new IllegalArgumentException("duplicate key in base: " + keys[i]);
            }
        }
        return new BaseSegment(keys, vectors, ordinals, revision, structure);
    }

    int size() {
        return keys.length;
    }

    int ordinalOf(Object key) {
        Integer ordinal = ordinals.get(key);
        return ordinal == null ? -1 : ordinal;
    }

    boolean optimized() {
        return structure != null;
    }

    boolean tryRetain() {
        while (true) {
            int current = references.get();
            if (current <= 0) {
                return false;
            }
            if (references.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    void retain() {
        if (!tryRetain()) {
            throw new IllegalStateException("base segment already released");
        }
    }

    void release() {
        int left = references.decrementAndGet();
        if (left == 0 && structure != null) {
            structure.close();
        } else if (left < 0) {
            throw new IllegalStateException("base segment released more times than retained");
        }
    }
}
