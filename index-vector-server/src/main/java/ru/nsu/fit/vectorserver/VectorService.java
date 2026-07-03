package ru.nsu.fit.vectorserver;

import org.apache.ignite.client.ClientCache;
import org.apache.ignite.client.IgniteClient;
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

    private final Index index;

    public VectorService(Index index) { // new VectorService(new BruteForceIndex())
        this.index = index;
    }

    public ResponseEntity<?> add(AddRequest request) {
        try {
            index.add(request);
            VectorResponse response = new VectorResponse(
                    request.id(), request.vector(), request.url(), request.metadata());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e){
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    public ResponseEntity<?> get(Long id) {
        try {
            VectorObject obj = index.get(id);
            if (obj == null) {
                return ResponseEntity.notFound().build();
            }
            VectorResponse response = new VectorResponse(id, obj.getVector(), obj.getUrl(), obj.getMetadata());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e){
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    public ResponseEntity<?> search(SearchRequest request) {
        try {
            List<Neighbor> result = index.search(request.vector(), request.count());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e){
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

}
