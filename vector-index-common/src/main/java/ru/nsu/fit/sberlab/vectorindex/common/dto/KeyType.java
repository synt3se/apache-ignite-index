package ru.nsu.fit.sberlab.vectorindex.common.dto;

import java.util.UUID;

/**
 * Типы ключей источника, которые индекс v1 принимает и возвращает. Это классы JDK: ответ
 * проходит через compute и thin client без классов приложения на координаторе и клиенте.
 * Ключ другого типа не индексируется и учитывается в готовности ({@code unsupportedKeysTotal}).
 * Тип передаётся вместе с ключом: JSON не различает строку и UUID.
 */
public enum KeyType {
    LONG,
    STRING,
    UUID;

    /** Тип ключа или {@code null}, если ключ не поддерживается. */
    public static KeyType of(Object key) {
        if (key instanceof Long) {
            return LONG;
        }
        if (key instanceof String) {
            return STRING;
        }
        if (key instanceof java.util.UUID) {
            return UUID;
        }
        return null;
    }

    /** Обратное преобразование внешнего представления ключа (REST). */
    public Object parse(String text) {
        return switch (this) {
            case LONG -> Long.parseLong(text);
            case STRING -> text;
            case UUID -> java.util.UUID.fromString(text);
        };
    }
}
