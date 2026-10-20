package ru.nsu.fit.sberlab.vectorindex.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ru.nsu.fit.sberlab.vectorindex.core.algorithm.FrozenVectorView;

/** Материализованный стабильный вход сборки: живые записи base ∪ frozen по единому правилу. */
final class MaterializedInput implements FrozenVectorView {

    final Object[] keys;
    final float[][] vectors;
    private final VectorSpec spec;

    private MaterializedInput(Object[] keys, float[][] vectors, VectorSpec spec) {
        this.keys = keys;
        this.vectors = vectors;
        this.spec = spec;
    }

    /** O(n) по захваченным неизменяемым данным; вызывается в потоке сборки, не под замком. */
    static MaterializedInput of(BaseSegment base, List<DeltaLayer> frozen, VectorSpec spec) {
        Map<Object, Entry> effective = FrozenGroup.effectiveEntries(base, frozen);
        List<Object> keys = new ArrayList<>(base.size() + effective.size());
        List<float[]> vectors = new ArrayList<>(base.size() + effective.size());
        for (int ordinal = 0; ordinal < base.size(); ordinal++) {
            Object key = base.keys[ordinal];
            if (!effective.containsKey(key)) {
                keys.add(key);
                vectors.add(base.vectors[ordinal]);
            }
        }
        effective.forEach((key, entry) -> {
            if (entry.live()) {
                keys.add(key);
                vectors.add(entry.vector());
            }
        });
        return new MaterializedInput(keys.toArray(), vectors.toArray(new float[0][]), spec);
    }

    @Override
    public int size() {
        return keys.length;
    }

    @Override
    public VectorSpec spec() {
        return spec;
    }

    @Override
    public float[] vector(int ordinal) {
        return vectors[ordinal];
    }
}
