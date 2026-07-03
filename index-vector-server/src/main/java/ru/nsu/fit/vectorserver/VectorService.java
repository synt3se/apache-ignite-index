package ru.nsu.fit.vectorserver;

import org.apache.ignite.client.ClientCache;
import org.apache.ignite.client.IgniteClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import ru.nsu.fit.vectorserver.core.Index;
import ru.nsu.fit.vectorserver.dto.AddRequest;

import org.apache.ignite.cache.query.QueryCursor;
import org.apache.ignite.cache.query.ScanQuery;
import ru.nsu.fit.vectorserver.dto.Neighbor;
import ru.nsu.fit.vectorserver.dto.SearchRequest;
import ru.nsu.fit.vectorserver.dto.VectorResponse;

import javax.cache.Cache;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;


@Service
public class VectorService {
    static final Logger log = LoggerFactory.getLogger(VectorService.class);
    private final Index index;

    public VectorService(Index index) { // new VectorService(new BruteForceIndex())
        this.index = index;
    }

    public ResponseEntity<?> add(AddRequest request) {
        try {
            log.info("Received AddRequest");
            index.add(request);
            VectorResponse response = new VectorResponse(
                    request.id(), request.vector(), request.url(), request.metadata());
            log.info("AddRequest processed. VectorResponse: " + response);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e){
            log.error("AddRequest process was broken", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    public ResponseEntity<?> get(Long id) {
        try {
            log.info("Received GetRequest");
            VectorObject obj = index.get(id);
            if (obj == null) {
                return ResponseEntity.notFound().build();
            }
            VectorResponse response = new VectorResponse(id, obj.getVector(), obj.getUrl(), obj.getMetadata());
            log.info("GetRequest processed. VectorResponse: " + response);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e){
            log.error("GetRequest process was broken", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    public ResponseEntity<?> search(SearchRequest request) {
        try {
            log.info("Received SearchRequest");
            List<Neighbor> result = index.search(request.vector(), request.count());
            log.info("GetRequest processed. Neighbors: " + result);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e){
            log.error("SearchRequest process was broken", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

}
