package ru.nsu.fit.sberlab.vectorindex.node.index;

/**
 * Что индекс видит в записи источника. Решение адаптера: вектор — {@code upsert}; запись без
 * вектора или её отсутствие — {@code delete}; поле не того типа — запись исключается из
 * проекции и учитывается в готовности ({@code wrongTypeValuesTotal}), тихого пропуска нет.
 */
sealed interface SourceValue {

    SourceValue ABSENT = new Absent();
    SourceValue NO_VECTOR = new NoVector();

    /** Принятый к индексации {@code float[]} (копию делает ядро). */
    record Vector(float[] vector) implements SourceValue {
    }

    /** Записи нет. */
    record Absent() implements SourceValue {
    }

    /** Запись есть, поля вектора нет или оно {@code null}: не индексируется. */
    record NoVector() implements SourceValue {
    }

    /** Поле вектора не {@code float[]} (или значение не объект с полями). */
    record WrongType(String type) implements SourceValue {
    }
}
