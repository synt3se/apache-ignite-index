package ru.nsu.fit.sberlab.vectorindex.core;

import ru.nsu.fit.sberlab.vectorindex.core.algorithm.OrdinalFilter;

/** Битовая маска перекрытых ordinals базы; фильтр пропускает неперекрытые. */
final class OrdinalMask implements OrdinalFilter {

    private final long[] words;

    OrdinalMask(int size) {
        words = new long[(size + 63) >>> 6];
    }

    private OrdinalMask(long[] words) {
        this.words = words;
    }

    OrdinalMask copy() {
        return new OrdinalMask(words.clone());
    }

    void set(int ordinal) {
        words[ordinal >>> 6] |= 1L << ordinal;
    }

    boolean get(int ordinal) {
        return (words[ordinal >>> 6] & (1L << ordinal)) != 0;
    }

    int cardinality() {
        int count = 0;
        for (long word : words) {
            count += Long.bitCount(word);
        }
        return count;
    }

    @Override
    public boolean accept(int ordinal) {
        return !get(ordinal);
    }
}
