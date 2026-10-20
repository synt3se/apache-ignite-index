package ru.nsu.fit.sberlab.vectorindex.node.index;

import org.apache.ignite.binary.BinaryObject;

/**
 * Вектор из значения кэша в бинарном виде ({@code withKeepBinary()}): читается одно поле
 * {@code BinaryObject}, значение целиком не десериализуется, класс значения на узле не нужен.
 * Без поля в определении значение кэша само должно быть {@code float[]}.
 */
final class VectorExtractor {

    private VectorExtractor() {
    }

    static SourceValue extract(Object value, String field) {
        if (value == null) {
            return SourceValue.ABSENT;
        }
        if (field == null) {
            return value instanceof float[] vector ? new SourceValue.Vector(vector) : wrongType(value);
        }
        if (!(value instanceof BinaryObject object)) {
            return wrongType(value);
        }
        if (!object.hasField(field)) {
            return SourceValue.NO_VECTOR;
        }
        Object raw = object.field(field);
        if (raw == null) {
            return SourceValue.NO_VECTOR;
        }
        return raw instanceof float[] vector ? new SourceValue.Vector(vector) : wrongType(raw);
    }

    private static SourceValue wrongType(Object value) {
        String type = value instanceof BinaryObject object ? object.type().typeName() : value.getClass().getName();
        return new SourceValue.WrongType(type);
    }
}
