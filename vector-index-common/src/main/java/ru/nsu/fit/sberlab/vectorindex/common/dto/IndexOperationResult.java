package ru.nsu.fit.sberlab.vectorindex.common.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Результат административной операции над индексом по узлам. {@link #confirmed} — операция
 * подтверждена всеми серверами топологии; при недоступном узле подтверждения нет.
 */
public class IndexOperationResult implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Outcome {
        DONE,
        /** Выгрузка: индекс уже был выгружен на узле. */
        ALREADY_UNLOADED,
        /** Узел не знает такого {@code indexId}. */
        UNKNOWN_INDEX,
        FAILED,
        NO_RESPONSE
    }

    public static class NodeOutcome implements Serializable {
        private static final long serialVersionUID = 1L;

        public String nodeId;
        public Outcome outcome;
        public String message;

        public NodeOutcome() {
        }

        public NodeOutcome(String nodeId, Outcome outcome, String message) {
            this.nodeId = nodeId;
            this.outcome = outcome;
            this.message = message;
        }

        @Override
        public String toString() {
            return nodeId + ":" + outcome + (message == null ? "" : " (" + message + ")");
        }
    }

    public String indexId;
    public String operation;
    public int expectedNodes;
    public List<NodeOutcome> nodes = new ArrayList<>();
    public boolean confirmed;
    /** Почему операция отклонена или не подтверждена; {@code null} — подробности в {@link #nodes}. */
    public String message;
    /** Операция отклонена до выполнения (например, {@code truncate} кэша, общего для нескольких индексов). */
    public boolean refused;

    public IndexOperationResult() {
    }
}
