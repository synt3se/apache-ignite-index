package ru.nsu.fit.sberlab.vectorindex.core;

/** Неизменяемый слой дельты {@code key → Entry} с учётом числа tombstone. */
final class DeltaLayer {

    static final DeltaLayer EMPTY = new DeltaLayer(LayerMap.empty(), 0);

    final LayerMap<Object, Entry> entries;
    final int tombstones;

    private DeltaLayer(LayerMap<Object, Entry> entries, int tombstones) {
        this.entries = entries;
        this.tombstones = tombstones;
    }

    Entry get(Object key) {
        return entries.get(key);
    }

    DeltaLayer put(Object key, Entry entry) {
        Entry previous = entries.get(key);
        int count = tombstones;
        if (previous != null && !previous.live()) {
            count--;
        }
        if (!entry.live()) {
            count++;
        }
        return new DeltaLayer(entries.plus(key, entry), count);
    }

    int size() {
        return entries.size();
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }
}
