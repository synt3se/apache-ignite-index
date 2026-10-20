package ru.nsu.fit.sberlab.vectorindex.vectorserver.rest;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
