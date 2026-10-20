package ru.nsu.fit.sberlab.vectorindex.common.dto;

import java.io.Serializable;

/** Ожидаемая партиция, не участвовавшая в поиске, и причина. */
public class PartitionFailure implements Serializable {

    public enum Reason {
        /** В назначении запроса у партиции нет primary-владельца. */
        NO_OWNER,
        /** На назначенном узле нет runtime этой партиции (владение сменилось). */
        NO_RUNTIME,
        /** Runtime ещё в начальной загрузке. */
        LOADING,
        /** Runtime закрыт во время запроса. */
        CLOSED,
        /** Бюджет запроса исчерпан до обработки партиции. */
        BUDGET,
        /** Пул поиска узла переполнен. */
        OVERLOADED,
        /** Ошибка поиска по партиции. */
        ERROR,
        /** Узел не знает индекса с таким {@code indexId}. */
        NO_INDEX,
        /** Индекс на узле настроен, но выгружен (до рестарта узла). */
        INDEX_UNLOADED,
        /** Определение индекса на узле отличается от определения координатора (отпечаток). */
        DEFINITION_MISMATCH,
        /** Узел не ответил в пределах бюджета. */
        NODE_TIMEOUT,
        /** Ответ узла завершился ошибкой. */
        NODE_FAILED,
        /** Узел вернул пустой ответ. */
        NODE_NO_RESPONSE,
        /** Узел не сообщил о назначенной партиции. */
        NOT_REPORTED
    }

    public int partition;
    public Reason reason;
    public String node;

    public PartitionFailure() {
    }

    public PartitionFailure(int partition, Reason reason, String node) {
        this.partition = partition;
        this.reason = reason;
        this.node = node;
    }

    @Override
    public String toString() {
        return partition + ":" + reason + (node == null ? "" : "@" + node);
    }
}
