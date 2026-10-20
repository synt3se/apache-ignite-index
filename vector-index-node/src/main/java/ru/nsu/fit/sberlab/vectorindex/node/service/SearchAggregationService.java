package ru.nsu.fit.sberlab.vectorindex.node.service;

import java.util.List;

import org.apache.ignite.services.Service;

import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexInfo;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexOperationResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchRequest;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchResponse;

/**
 * Поисковая и административная плоскость индексов (node-singleton). Поиск возвращает ключи,
 * расстояния и полный статус без документов; гидрация — в прикладном слое.
 */
public interface SearchAggregationService extends Service {
    String NAME = "SearchAggregationService";

    /** Ошибки запроса возвращаются в ответе ({@code error}), а не исключением. */
    VectorSearchResponse search(VectorSearchRequest request);

    /** Индексы, настроенные на узле-координаторе. */
    List<IndexInfo> indexes();

    /** Готовность индекса по всем серверам; {@code null} — координатор не знает индекса. */
    IndexStatus status(String indexId);

    /**
     * Выгрузка настроенного индекса на всех серверах (до их рестарта); результат по узлам.
     * {@code null} — координатор не знает индекса.
     */
    IndexOperationResult unload(String indexId);

    /** Полная сверка индекса на всех серверах; {@code null} — координатор не знает индекса. */
    IndexOperationResult rebuild(String indexId);
}
