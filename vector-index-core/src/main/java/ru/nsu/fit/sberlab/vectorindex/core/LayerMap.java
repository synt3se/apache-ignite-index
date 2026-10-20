package ru.nsu.fit.sberlab.vectorindex.core;

import java.util.function.BiConsumer;

import org.pcollections.HashPMap;
import org.pcollections.HashTreePMap;

/**
 * Неизменяемое отображение слоя дельты со структурным разделением: {@link #plus} копирует
 * только путь до изменённого элемента, а не всю карту. Удаление не нужно — удаление записи
 * выражается tombstone, а слои отбрасываются целиком при публикации.
 *
 * <p>Реализация скрыта за интерфейсом, чтобы заменить библиотеку после измерений (ADR-1).
 */
final class LayerMap<K, V> {

    private static final LayerMap<?, ?> EMPTY = new LayerMap<>(HashTreePMap.empty());

    private final HashPMap<K, V> map;

    private LayerMap(HashPMap<K, V> map) {
        this.map = map;
    }

    @SuppressWarnings("unchecked")
    static <K, V> LayerMap<K, V> empty() {
        return (LayerMap<K, V>) EMPTY;
    }

    V get(Object key) {
        return map.get(key);
    }

    LayerMap<K, V> plus(K key, V value) {
        return new LayerMap<>(map.plus(key, value));
    }

    int size() {
        return map.size();
    }

    boolean isEmpty() {
        return map.isEmpty();
    }

    /** Порядок обхода детерминирован для одинакового содержимого (по хэшу ключа). */
    void forEach(BiConsumer<? super K, ? super V> action) {
        map.forEach(action);
    }
}
