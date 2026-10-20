package ru.nsu.fit.sberlab.vectorindex.core.algorithm;

/** Ограниченная куча k лучших ordinals по расстоянию без boxing. Не потокобезопасна. */
public final class TopOrdinals {

    private final int capacity;
    private final int[] ordinals;
    private final double[] distances;
    private int size;

    public TopOrdinals(int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }
        capacity = k;
        ordinals = new int[k];
        distances = new double[k];
    }

    /** @return {@code true}, если кандидат вошёл в текущий top-k */
    public boolean offer(int ordinal, double distance) {
        if (size < capacity) {
            ordinals[size] = ordinal;
            distances[size] = distance;
            siftUp(size++);
            return true;
        }
        if (distance >= distances[0]) {
            return false;
        }
        ordinals[0] = ordinal;
        distances[0] = distance;
        siftDown(0);
        return true;
    }

    public int size() {
        return size;
    }

    public void forEach(CandidateConsumer consumer) {
        for (int i = 0; i < size; i++) {
            consumer.accept(ordinals[i]);
        }
    }

    // max-куча: в корне худший из лучших
    private void siftUp(int i) {
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (distances[parent] >= distances[i]) {
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
            if (right < size && distances[right] > distances[left]) {
                worst = right;
            }
            if (distances[i] >= distances[worst]) {
                return;
            }
            swap(i, worst);
            i = worst;
        }
    }

    private void swap(int a, int b) {
        int o = ordinals[a];
        ordinals[a] = ordinals[b];
        ordinals[b] = o;
        double d = distances[a];
        distances[a] = distances[b];
        distances[b] = d;
    }
}
