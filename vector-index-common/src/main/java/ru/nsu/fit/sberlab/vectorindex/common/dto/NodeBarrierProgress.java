package ru.nsu.fit.sberlab.vectorindex.common.dto;

import java.io.Serializable;

/**
 * Барьер загрузки на одном узле: партиции и экземпляры runtime, для которых выдан тикет
 * сверки, и их продвижение (скан после тикета, принудительная оптимизация).
 */
public class NodeBarrierProgress implements Serializable {

    private static final long serialVersionUID = 1L;

    public String nodeId;
    /** Причина, по которой узел не участвует в барьере; {@code null} — участвует. */
    public String error;
    /** Узел не знает такого барьера (рестарт узла, выгрузка индекса): барьер нужно начать заново. */
    public boolean unknownBarrier;
    /** Барьер ждёт и принудительной оптимизации после скана. */
    public boolean optimize;
    /** Версия топологии на узле в момент выдачи тикетов. */
    public long topologyVersion;
    /** Партиции барьера: runtime, существовавшие на узле при выдаче тикетов. */
    public int[] partitions = new int[0];
    /** Опубликован скан, начатый после тикета. */
    public int[] scannedPartitions = new int[0];
    /** После скана построена структура текущего состояния, active и frozen пусты, сборок нет. */
    public int[] optimizedPartitions = new int[0];
    /** Последняя сборка завершилась ошибкой (сервис сборок повторит её). */
    public int[] failedBuildPartitions = new int[0];
    /** Runtime барьера заменён или закрыт (смена владельца, выгрузка): барьер недействителен. */
    public int[] invalidatedPartitions = new int[0];
    /** Runtime текущего эпизода, которых нет в барьере (партиция пришла на узел после тикетов). */
    public int[] foreignPartitions = new int[0];
    public long pendingApplies;
    public int scansInProgress;
    public long liveVectors;

    public NodeBarrierProgress() {
    }
}
