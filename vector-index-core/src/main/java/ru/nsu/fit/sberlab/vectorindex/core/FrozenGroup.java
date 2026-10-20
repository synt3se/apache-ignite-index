package ru.nsu.fit.sberlab.vectorindex.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Пара (база, frozen-слои) и лениво вычисляемое разрешение frozen-слоёв относительно базы.
 * Пара меняется только при capture/publish, поэтому разрешение переиспользуется всеми
 * состояниями между ними — поиск не пересчитывает frozen на каждый запрос. Разрешение
 * прогревается сразу после capture; поиск, попавший в окно до прогрева, готовит его в своём бюджете.
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

    /** Без ограничения времени: чтение содержимого и прогрев после capture. */
    Resolution resolution() {
        return resolution(null);
    }

    /**
     * В бюджете запроса ({@code null} — без ограничения). Прерванная по бюджету подготовка
     * возвращает {@code null} и ничего не кэширует. Завершённая кэшируется, даже если бюджет
     * истёк к её концу: она корректна, а решение о следующем этапе принимает поиск.
     */
    Resolution resolution(Budget budget) {
        Resolution r = resolution;
        if (r == null) {
            r = resolve(base, frozen, budget);
            if (r != null) {
                resolution = r; // гонка безвредна: результат детерминирован и неизменяем
            }
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
        return effectiveEntries(base, frozen, null);
    }

    /** @return {@code null}, если обход прерван по бюджету */
    private static Map<Object, Entry> effectiveEntries(BaseSegment base, List<DeltaLayer> frozen, Budget budget) {
        Map<Object, Entry> effective = new LinkedHashMap<>();
        int[] visited = {0};
        for (int i = frozen.size() - 1; i >= 0; i--) {
            boolean finished = frozen.get(i).entries.forEachWhile((key, entry) -> {
                if (budget != null && (visited[0]++ & Budget.CHECK_MASK) == 0 && budget.exhausted()) {
                    return false;
                }
                // Записи ниже по стеку старше: если верхняя для ключа не действует, не действуют и они.
                if (!effective.containsKey(key) && entry.revision() > base.revision) {
                    effective.put(key, entry);
                } else if (!effective.containsKey(key)) {
                    effective.put(key, null); // ключ перекрыт базой: ниже не смотреть
                }
                return true;
            });
            if (!finished) {
                return null;
            }
        }
        effective.values().removeIf(entry -> entry == null);
        return effective;
    }

    private static Resolution resolve(BaseSegment base, List<DeltaLayer> frozen, Budget budget) {
        if (frozen.isEmpty()) {
            return new Resolution(Map.of(), null, new Object[0], new float[0][]);
        }
        Map<Object, Entry> effective = effectiveEntries(base, frozen, budget);
        if (effective == null) {
            return null;
        }
        OrdinalMask mask = null;
        List<Object> keys = new ArrayList<>();
        List<float[]> vectors = new ArrayList<>();
        int visited = 0;
        for (Map.Entry<Object, Entry> e : effective.entrySet()) {
            if (budget != null && (visited++ & Budget.CHECK_MASK) == 0 && budget.exhausted()) {
                return null;
            }
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
