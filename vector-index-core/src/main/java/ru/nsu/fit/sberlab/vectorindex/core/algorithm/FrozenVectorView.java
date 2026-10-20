package ru.nsu.fit.sberlab.vectorindex.core.algorithm;

import ru.nsu.fit.sberlab.vectorindex.core.VectorSpec;

/**
 * Стабильный вход сборки: живые записи захваченных base ∪ frozen (верхний слой выигрывает,
 * tombstone исключены), пронумерованные ordinals {@code 0..size-1}. Не читает текущий источник
 * и не меняется во время сборки. Ключей алгоритм не видит.
 *
 * <p><b>Доверенный доступ без копий.</b> Массивы из {@link #vector} принадлежат ядру и
 * неизменяемы: алгоритм обязан только читать их. Построенная {@link SearchStructure} может
 * удерживать ссылки на них, пока сама открыта; после {@link SearchStructure#close()} — нет.
 * Контрактный набор проверяет это контрольными суммами.
 */
public interface FrozenVectorView {

    int size();

    VectorSpec spec();

    float[] vector(int ordinal);
}
