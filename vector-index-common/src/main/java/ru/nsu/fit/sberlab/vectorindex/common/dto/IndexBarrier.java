package ru.nsu.fit.sberlab.vectorindex.common.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Барьер загрузки статического бенчмарка по кластеру (этап D). Последовательность:
 * записи подтверждены (их подтверждает вызывающий) → принятые применения завершены → на каждой
 * партиции выдан новый тикет сверки → опубликован скан, начатый после тикета → (при
 * {@code optimize}) принудительно построена структура текущего состояния → готовность проверена.
 *
 * <p>Идентификатор связан с определением (отпечаток), версией топологии и экземплярами runtime
 * на узлах: смена топологии, замена runtime или потеря барьера узлом дают {@link State#RESTART}.
 */
public class IndexBarrier implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum State {
        /** Тикеты выданы на всех серверах; ждать {@link #COMPLETE}. */
        STARTED,
        IN_PROGRESS,
        /** Все ожидаемые партиции прошли скан после тикета (и оптимизацию, если запрошена). */
        COMPLETE,
        /** Топология или runtime изменились, узел потерял барьер: начать новый барьер. */
        RESTART,
        /** Барьер невозможен: узел не знает индекс, другое определение, применения не завершились. */
        FAILED
    }

    public String barrierId;
    public String indexId;
    public String fingerprint;
    public boolean optimize;
    public State state;
    public String message;
    public long topologyVersion;
    public int expectedPartitions;
    public int scannedPartitions;
    public int optimizedPartitions;
    /** Ожидаемые партиции, ещё не завершившие барьер у назначенного владельца. */
    public int[] waitingPartitions = new int[0];
    public int[] failedBuildPartitions = new int[0];
    public long liveVectors;
    public long pendingApplies;
    public int scansInProgress;
    /** Серверы, не ответившие на запрос. */
    public List<String> unknownNodes = new ArrayList<>();
    public List<NodeBarrierProgress> nodes = new ArrayList<>();

    public IndexBarrier() {
    }
}
