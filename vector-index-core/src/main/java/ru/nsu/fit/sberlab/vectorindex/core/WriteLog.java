package ru.nsu.fit.sberlab.vectorindex.core;

/**
 * Неизменяемый журнал ключей, записанных во время открытого скана (новые — в начале).
 * Нужен только для подсчёта {@code liveCount} при публикации скана; живёт до её завершения.
 */
final class WriteLog {

    static final WriteLog EMPTY = new WriteLog(null, null);

    final Object key;
    final WriteLog next;

    private WriteLog(Object key, WriteLog next) {
        this.key = key;
        this.next = next;
    }

    WriteLog push(Object key) {
        return new WriteLog(key, this);
    }
}
