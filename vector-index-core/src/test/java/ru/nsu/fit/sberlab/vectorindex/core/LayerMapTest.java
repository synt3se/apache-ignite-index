package ru.nsu.fit.sberlab.vectorindex.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

class LayerMapTest {

    @Test
    void matchesHashMapAndKeepsEveryOlderVersionIntact() {
        Random random = new Random(20261020L);
        LayerMap<Object, Integer> current = LayerMap.empty();
        Map<Object, Integer> model = new HashMap<>();
        List<LayerMap<Object, Integer>> versions = new ArrayList<>();
        List<Map<Object, Integer>> snapshots = new ArrayList<>();

        for (int step = 0; step < 20_000; step++) {
            // Половина ключей с одинаковым хэшем — проверяем и ветви коллизий.
            Object key = random.nextBoolean()
                    ? Long.valueOf(random.nextInt(2_000))
                    : new CollidingKey(random.nextInt(64));
            int value = random.nextInt();
            current = current.plus(key, value);
            model.put(key, value);
            if (step % 1_000 == 0) {
                versions.add(current);
                snapshots.add(new HashMap<>(model));
            }
        }

        assertThat(contents(current)).isEqualTo(model);
        assertThat(current.size()).isEqualTo(model.size());
        for (int i = 0; i < versions.size(); i++) {
            assertThat(contents(versions.get(i))).as("version %d", i).isEqualTo(snapshots.get(i));
        }
    }

    @Test
    void iterationOrderIsDeterministicForTheSameContent() {
        LayerMap<Object, Integer> forward = LayerMap.empty();
        LayerMap<Object, Integer> backward = LayerMap.empty();
        for (int i = 0; i < 500; i++) {
            forward = forward.plus((long) i, i);
            backward = backward.plus((long) (499 - i), 499 - i);
        }

        assertThat(new ArrayList<>(contents(forward).keySet()))
                .containsExactlyElementsOf(contents(backward).keySet());
    }

    private static Map<Object, Integer> contents(LayerMap<Object, Integer> map) {
        Map<Object, Integer> out = new LinkedHashMap<>();
        map.forEach(out::put);
        return out;
    }

    private record CollidingKey(int id) {
        @Override public int hashCode() { return 42; }
    }
}
