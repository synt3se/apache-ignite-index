package ru.nsu.fit.sberlab.vectorindex.core;

import java.util.function.BiConsumer;

/**
 * Согласованный локальный вид одного состояния runtime. Открытие не блокирует писателей,
 * запись не ждёт поисков. Вид удерживает базу (и её структуру) до {@link #close()};
 * логическое содержимое вида не меняется после открытия.
 */
public interface ReadView<K> extends AutoCloseable {

    /** Фаза на момент открытия. {@link Phase#LOADING} — вид не засчитывается в покрытие. */
    Phase phase();

    /** Готовность и метрики runtime; состояние слоёв — этого вида. */
    RuntimeStatus status();

    /** Логическое число живых записей, O(1). */
    long size();

    /** Копия действующего вектора ключа или {@code null}. */
    float[] get(K key);

    /** Обход логического содержимого с копиями векторов: диагностика и сверка, O(n·d). */
    void forEachLive(BiConsumer<? super K, float[]> action);

    /**
     * @throws IllegalArgumentException неверный запрос или параметры чужого алгоритма
     * @throws IllegalStateException    вид уже закрыт
     */
    LocalSearchResult<K> search(float[] query, SearchParams params);

    /** Идемпотентно отпускает базу. */
    @Override
    void close();
}
