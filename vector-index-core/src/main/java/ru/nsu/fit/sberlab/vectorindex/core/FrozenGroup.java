package ru.nsu.fit.sberlab.vectorindex.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Пара (база, frozen-слои) и лениво вычисляемое разрешение frozen-слоёв относительно базы.
 * Пара меняется только при capture/publish, поэтому разрешение переиспользуется всеми
 * состояниями между ними — поиск не пересчитывает frozen на каждый запрос.
 */
final class FrozenGroup {

    final BaseSegment base;
    final List<DeltaLayer> frozen;
    private volatile Resolution resolution;

    FrozenGroup(BaseSegment base, List<DeltaLayer> frozen) {
        this.base = base;
        this.frozen = frozen;
    }

    FrozenGroup sameAs(BaseSegment otherBase, List<DeltaLayer> otherFrozen) {
        return base == otherBase && frozen == otherFrozen ? this : new FrozenGroup(otherBase, otherFrozen);
    }

    Resolution resolution() {
        Resolution r = resolution;
        if (r == null) {
            r = resolve(base, frozen);
            resolution = r; // гонка безвредна: результат детерминирован и неизменяем
        }
        return r;
    }

    /**
     * @param effective действующие записи frozen (верхний слой выигрывает, ревизия выше базы)
     * @param mask      ordinals базы, перекрытые действующими записями, или {@code null}
     * @param liveKeys  ключи живых действующих записей
     * @param liveVectors их векторы
     */
    record Resolution(Map<Object, Entry> effective, OrdinalMask mask, Object[] liveKeys, float[][] liveVectors) {
    }

    static Map<Object, Entry> effectiveEntries(BaseSegment base, List<DeltaLayer> frozen) {
        Map<Object, Entry> effective = new LinkedHashMap<>();
        for (int i = frozen.size() - 1; i >= 0; i--) {
            frozen.get(i).entries.forEach((key, entry) -> {
                // Записи ниже по стеку старше: если верхняя для ключа не действует, не действуют и они.
                if (!effective.containsKey(key) && entry.revision() > base.revision) {
                    effective.put(key, entry);
                } else if (!effective.containsKey(key)) {
                    effective.put(key, null); // ключ перекрыт базой: ниже не смотреть
                }
            });
        }
        effective.values().removeIf(entry -> entry == null);
        return effective;
    }

    private static Resolution resolve(BaseSegment base, List<DeltaLayer> frozen) {
        if (frozen.isEmpty()) {
            return new Resolution(Map.of(), null, new Object[0], new float[0][]);
        }
        Map<Object, Entry> effective = effectiveEntries(base, frozen);
        OrdinalMask mask = null;
        List<Object> keys = new ArrayList<>();
        List<float[]> vectors = new ArrayList<>();
        for (Map.Entry<Object, Entry> e : effective.entrySet()) {
            int ordinal = base.ordinalOf(e.getKey());
            if (ordinal >= 0) {
                if (mask == null) {
                    mask = new OrdinalMask(base.size());
                }
                mask.set(ordinal);
            }
            if (e.getValue().live()) {
                keys.add(e.getKey());
                vectors.add(e.getValue().vector());
            }
        }
        return new Resolution(effective, mask, keys.toArray(), vectors.toArray(new float[0][]));
    }
}
