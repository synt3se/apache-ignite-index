package ru.nsu.fit.sberlab.vectorindex.core.algorithm;

/**
 * Какие локальные ordinals базы допустимы в результате. Ядро передаёт битовую маску
 * перекрытых и удалённых записей — проверка вершины не требует boxed-поиска.
 * Алгоритм может обходить недопустимые вершины, но не возвращать их.
 */
@FunctionalInterface
public interface OrdinalFilter {

    OrdinalFilter ALL = ordinal -> true;

    boolean accept(int ordinal);
}
