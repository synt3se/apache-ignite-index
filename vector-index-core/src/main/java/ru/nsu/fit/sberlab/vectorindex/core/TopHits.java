package ru.nsu.fit.sberlab.vectorindex.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** k лучших ключей по расстоянию; при равенстве — в порядке поступления. Не потокобезопасна. */
final class TopHits {

    private final int capacity;
    private final Object[] keys;
    private final double[] distances;
    private final long[] order;
    private long sequence;
    private int size;

    TopHits(int k) {
        capacity = k;
        keys = new Object[k];
        distances = new double[k];
        order = new long[k];
    }

    void offer(Object key, double distance) {
        if (size < capacity) {
            keys[size] = key;
            distances[size] = distance;
            order[size] = sequence++;
            siftUp(size++);
            return;
        }
        if (!worse(0, distance, sequence)) {
            return;
        }
        keys[0] = key;
        distances[0] = distance;
        order[0] = sequence++;
        siftDown(0);
    }

    @SuppressWarnings("unchecked")
    <K> List<Hit<K>> sorted() {
        Integer[] index = new Integer[size];
        for (int i = 0; i < size; i++) {
            index[i] = i;
        }
        Arrays.sort(index, (a, b) -> {
            int byDistance = Double.compare(distances[a], distances[b]);
            return byDistance != 0 ? byDistance : Long.compare(order[a], order[b]);
        });
        List<Hit<K>> hits = new ArrayList<>(size);
        for (Integer i : index) {
            hits.add(new Hit<>((K) keys[i], distances[i]));
        }
        return hits;
    }

    /** Хуже ли элемент кучи {@code i}, чем кандидат (distance, seq). */
    private boolean worse(int i, double distance, long seq) {
        int c = Double.compare(distances[i], distance);
        return c > 0 || (c == 0 && order[i] > seq);
    }

    private boolean worse(int a, int b) {
        return worse(a, distances[b], order[b]);
    }

    private void siftUp(int i) {
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (!worse(i, parent)) {
                return;
            }
            swap(i, parent);
            i = parent;
        }
    }

    private void siftDown(int i) {
        while (true) {
            int left = 2 * i + 1;
            if (left >= size) {
                return;
            }
            int worst = left;
            int right = left + 1;
            if (right < size && worse(right, left)) {
                worst = right;
            }
            if (!worse(worst, i)) {
                return;
            }
            swap(i, worst);
            i = worst;
        }
    }

    private void swap(int a, int b) {
        Object k = keys[a];
        keys[a] = keys[b];
        keys[b] = k;
        double d = distances[a];
        distances[a] = distances[b];
        distances[b] = d;
        long o = order[a];
        order[a] = order[b];
        order[b] = o;
    }
}
