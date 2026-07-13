package ru.nsu.fit.vectorserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import ru.nsu.fit.vector.common.dto.*;
import ru.nsu.fit.vectorserver.exception.ResourceNotFoundException;
import ru.nsu.fit.vectorserver.index.Index;

import ru.nsu.fit.vector.common.VectorObject;

import java.util.List;
import java.util.Map;

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
        log.info("Received AddRequest");
        long id = idGenerator.nextId();
        index.add(id, request);
        VectorResponse response = new VectorResponse(
                id, request.vector(), request.url(), request.metadata());
        log.info("AddRequest processed. VectorResponse: " + response);
        return ResponseEntity.ok(response);
    }

    public ResponseEntity<VectorResponse> get(Long id) {
        log.info("Received GetRequest");
        VectorObject obj = index.get(id);
        if (obj == null) {
            throw new ResourceNotFoundException("Vector with id " + id + " not found");
        }
        VectorResponse response = new VectorResponse(id, obj.getVector(), obj.getUrl(), obj.getMetadata());
        log.info("GetRequest processed. VectorResponse: " + response);
        return ResponseEntity.ok(response);
    }

    public ResponseEntity<List<Neighbor>> search(SearchRequest request) {
        log.info("Received SearchRequest");
        List<Neighbor> result = index.search(request.vector(), request.count());
        log.info("SearchRequest processed. Neighbors: " + result);
        return ResponseEntity.ok(result);
    }

    public ResponseEntity<String> save(SaveRequest request) {
        log.info("Received SaveRequest");
        index.save(request.file());
        log.info("SaveRequest processed");
        return ResponseEntity.ok("SaveRequest processed");
    }

    public ResponseEntity<String> load(LoadRequest request) {
        log.info("Received LoadRequest для пути: {}", request.file());

        clear();

        long maxId = index.load(request.file());
        idGenerator.setCounter(maxId);

        log.info("LoadRequest processed. New ID counter: {}", maxId);
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
}
