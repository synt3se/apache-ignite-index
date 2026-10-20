package ru.nsu.fit.sberlab.vectorindex.vectorserver;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.Counter;
import ru.nsu.fit.sberlab.vectorindex.common.dto.*;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.exception.ResourceNotFoundException;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.index.Index;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;

import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;

import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

@Service
public class VectorService {
    static final Logger log = LoggerFactory.getLogger(VectorService.class);
    private final Index index;
    private final IdGenerator idGenerator;

    public VectorService(Index index, IdGenerator idGenerator) {
        this.index = index;
        this.idGenerator = idGenerator;
    }

    public ResponseEntity<VectorResponse> add(AddRequest request) {
        log.debug("Received AddRequest");
        long id = idGenerator.nextId();
        index.add(id, request);
        VectorResponse response = new VectorResponse(
                id, request.vector(), request.url(), request.metadata());
        log.debug("AddRequest processed. VectorResponse: " + response);
        return ResponseEntity.ok(response);
    }

    public ResponseEntity<VectorResponse> get(Long id) {
        log.debug("Received GetRequest");
        VectorObject obj = index.get(id);
        if (obj == null) {
            throw new ResourceNotFoundException("Vector with id " + id + " not found");
        }
        VectorResponse response = new VectorResponse(id, obj.getVector(), obj.getUrl(), obj.getMetadata());
        log.debug("GetRequest processed. VectorResponse: " + response);
        return ResponseEntity.ok(response);
    }

    public ResponseEntity<Void> delete(Long id) {
        index.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Совместимость демо: тело — {@code List<Neighbor>} без статуса, как раньше; статус
     * выполнения — в заголовках {@code X-Vindex-*}. Полный статус — {@code /vectors/search/full}
     * и {@code /indexes/{id}/search}.
     */
    public ResponseEntity<List<Neighbor>> search(SearchRequest request) {
        log.debug("Received SearchRequest");
        ResponseEntity<List<Neighbor>> response = compatible(index.searchFull(request.vector(), request.count()));
        log.debug("SearchRequest processed. Neighbors: " + response.getBody());
        return response;
    }

    static ResponseEntity<List<Neighbor>> compatible(SearchResponse response) {
        List<Neighbor> neighbors = new java.util.ArrayList<>(response.results.size());
        for (SearchHit hit : response.results) {
            neighbors.add(new Neighbor(hit.id, hit.distance, hit.url, hit.metadata));
        }
        return ResponseEntity.ok()
                .header("X-Vindex-Partial", String.valueOf(response.partial))
                .header("X-Vindex-Timed-Out", String.valueOf(response.timedOut))
                .header("X-Vindex-Searched-Partitions", String.valueOf(response.activePartitions))
                .header("X-Vindex-Expected-Partitions", String.valueOf(response.totalPartitions))
                .body(neighbors);
    }

    public ResponseEntity<String> save(SaveRequest request) {
        log.debug("Received SaveRequest");
        index.save(request.file());
        log.debug("SaveRequest processed");
        return ResponseEntity.ok("SaveRequest processed");
    }

    public ResponseEntity<String> load(LoadRequest request) {
        log.debug("Received LoadRequest for path: {}", request.file());

        clear();

        long maxId = index.load(request.file());
        idGenerator.setCounter(maxId);

        log.debug("LoadRequest processed. New ID counter: {}", maxId);
        return ResponseEntity.ok("LoadRequest processed.");
    }

    public void clear(){
        index.clear();
    }

    public void addAll(Map<Long, VectorObject> vectors) {
        index.addAll(vectors);
    }

    public void rebuild() {
        index.rebuild();
    }

    public ResponseEntity<ClusterStats> stats() {
        return ResponseEntity.ok(index.stats());
    }

    public ResponseEntity<SearchResponse> searchFull(SearchRequest request) {
        return ResponseEntity.ok(index.searchFull(request.vector(), request.count()));
    }
}
