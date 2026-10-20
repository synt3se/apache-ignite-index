package ru.nsu.fit.sberlab.vectorindex.core;

/**
 * Явный результат применения изменения к runtime.
 *
 * @param status   что произошло
 * @param reason   причина отказа для {@link Status#REJECTED}, иначе {@code null}
 * @param revision ревизия записанной записи слоя или {@code -1}, если состояние не изменилось
 */
public record ApplyResult(Status status, RejectReason reason, long revision) {

    public enum Status {
        /** Записано в active-слой. */
        APPLIED,
        /** Логическое содержимое уже такое (повтор события, удаление отсутствующего ключа). */
        UNCHANGED,
        /** Вектор неверен: запись исключена из проекции (tombstone, если ключ был виден), учтена. */
        REJECTED,
        /** Runtime закрыт: изменение относится к прошлому эпизоду владения. */
        CLOSED
    }

    static final ApplyResult UNCHANGED = new ApplyResult(Status.UNCHANGED, null, -1);
    static final ApplyResult CLOSED = new ApplyResult(Status.CLOSED, null, -1);

    static ApplyResult applied(long revision) {
        return new ApplyResult(Status.APPLIED, null, revision);
    }

    static ApplyResult rejected(RejectReason reason, long revision) {
        return new ApplyResult(Status.REJECTED, reason, revision);
    }
}
