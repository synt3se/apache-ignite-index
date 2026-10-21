package ru.nsu.fit.sberlab.vectorindex.vectorserver.rest;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexBarrier;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexInfo;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexOperationResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchResponse;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.exception.ResourceNotFoundException;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.index.Index;

/**
 * Индексы по {@code indexId}: поиск без документов с полным статусом, готовность и операции.
 *
 * <ul>
 *   <li>неверный запрос — 400, неизвестный индекс — 404;</li>
 *   <li>неполный ответ при {@code allowPartial=false} — 503 с покрытием и причинами, без попаданий;</li>
 *   <li>операция, не подтверждённая всеми серверами, — 503 с результатом по узлам;</li>
 *   <li>{@code unload} — выгрузка настроенного индекса без долговечного каталога: после рестарта
 *       узла индекс снова загружается из конфигурации.</li>
 * </ul>
 */
@RestController
@RequestMapping("/indexes")
public class IndexController {

    private final Index index;

    public IndexController(Index index) {
        this.index = index;
    }

    @GetMapping
    public List<IndexInfo> indexes() {
        return index.indexes();
    }

    @GetMapping("/{indexId}/status")
    public IndexStatus status(@PathVariable String indexId) {
        IndexStatus status = index.status(indexId);
        if (status == null) {
            throw new ResourceNotFoundException("unknown index: " + indexId);
        }
        return status;
    }

    @PostMapping("/{indexId}/search")
    public ResponseEntity<IndexSearchView> search(@PathVariable String indexId, @RequestBody IndexSearchBody body) {
        VectorSearchResponse response = index.search(body.toRequest(indexId));
        return ResponseEntity.status(httpStatus(response)).body(IndexSearchView.of(response));
    }

    @PostMapping("/{indexId}/rebuild")
    public ResponseEntity<IndexOperationResult> rebuild(@PathVariable String indexId) {
        return operation(indexId, index.rebuild(indexId));
    }

    @PostMapping("/{indexId}/unload")
    public ResponseEntity<IndexOperationResult> unload(@PathVariable String indexId) {
        return operation(indexId, index.unload(indexId));
    }

    /** Пакетная загрузка {@code Long → float[]}; весь батч проверяется до записи (ошибка — 400/404). */
    @PutMapping("/{indexId}/vectors")
    public Map<String, Object> upload(@PathVariable String indexId, @RequestBody VectorBatchBody body) {
        int written = index.upload(indexId, body.ids(), body.vectors());
        return Map.of("indexId", indexId, "written", written);
    }

    /**
     * Очистка кэша индекса и самого индекса: 409 — кэш используют и другие индексы, 503 — не все
     * серверы подтвердили фазы.
     */
    @PostMapping("/{indexId}/truncate")
    public ResponseEntity<IndexOperationResult> truncate(@PathVariable String indexId) {
        IndexOperationResult result = index.truncate(indexId);
        if (result != null && result.refused) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
        }
        return operation(indexId, result);
    }

    /**
     * Барьер загрузки (этап D): вызывать после подтверждения всех записей. Ответ — состояние
     * барьера; {@code RESTART} — начать новый, {@code FAILED} — прервать загрузку.
     */
    @PostMapping("/{indexId}/barriers")
    public IndexBarrier beginBarrier(@PathVariable String indexId,
                                     @RequestParam(defaultValue = "true") boolean optimize) {
        return known(indexId, index.beginBarrier(indexId, optimize));
    }

    /** Продвижение барьера (идемпотентно): опрашивать до {@code COMPLETE}. */
    @PostMapping("/{indexId}/barriers/{barrierId}/progress")
    public IndexBarrier barrierProgress(@PathVariable String indexId, @PathVariable String barrierId) {
        return known(indexId, index.barrierProgress(indexId, barrierId));
    }

    private static IndexBarrier known(String indexId, IndexBarrier barrier) {
        if (barrier == null) {
            throw new ResourceNotFoundException("unknown index: " + indexId);
        }
        return barrier;
    }

    static HttpStatus httpStatus(VectorSearchResponse response) {
        if (response.error != null) {
            return switch (response.error) {
                case UNKNOWN_INDEX -> HttpStatus.NOT_FOUND;
                case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            };
        }
        return response.status == SearchStatus.FAILED ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
    }

    private static ResponseEntity<IndexOperationResult> operation(String indexId, IndexOperationResult result) {
        if (result == null) {
            throw new ResourceNotFoundException("unknown index: " + indexId);
        }
        return ResponseEntity.status(result.confirmed ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(result);
    }
}
