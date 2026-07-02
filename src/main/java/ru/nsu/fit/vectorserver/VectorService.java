package ru.nsu.fit.vectorserver;

import org.apache.ignite.client.ClientCache;
import org.apache.ignite.client.IgniteClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.nsu.fit.vectorserver.dto.AddRequest;

import org.apache.ignite.cache.query.QueryCursor;
import org.apache.ignite.cache.query.ScanQuery;
import ru.nsu.fit.vectorserver.dto.SearchRequest;
import ru.nsu.fit.vectorserver.dto.VectorResponse;

import javax.cache.Cache;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;


@Service
public class VectorService {
    private final ClientCache<Long, VectorObject> cache;

    public VectorService(IgniteClient igniteClient,
                         @Value("${ignite.cache.name}") String cacheName){
        this.cache = igniteClient.getOrCreateCache(cacheName);
    }

    public VectorObject save(AddRequest request) {
        validateSaveRequest(request);

        float[] vector = request.vector();

        VectorObject object = new VectorObject(
                vector,
                request.url()
        );

        cache.put(request.id(), object);

        return object;
    }

    public VectorObject get(Long id) {
        return cache.get(id);
    }

    private void validateSaveRequest(AddRequest request) { //TODO
        if (request.id() == null || request.id() <= 0) {
            throw new IllegalArgumentException("id is required");
        }

        if (request.url() == null || request.url().isBlank()) {
            throw new IllegalArgumentException("url is required");
        }

        if (request.vector() == null || request.vector().length == 0) {
            throw new IllegalArgumentException("vector is required");
        }
    }

    public List<VectorResponse> search(SearchRequest request) {
        validateSearchRequest(request);

        float[] queryVector = request.vector();
        int count = request.count();

        PriorityQueue<SearchCandidate> top = new PriorityQueue<>(
                Comparator.comparingDouble(SearchCandidate::distance).reversed()
        );

        try (QueryCursor<Cache.Entry<Long, VectorObject>> cursor =
                     cache.query(new ScanQuery<>())) {

            for (Cache.Entry<Long, VectorObject> entry : cursor) {
                Long id = entry.getKey();
                VectorObject object = entry.getValue();

                if (object == null || object.getVector() == null) {
                    continue;
                }

                float[] storedVector = object.getVector();

                double distance = euclideanDistance(queryVector, storedVector);

                SearchCandidate candidate = new SearchCandidate(
                        id,
                        object,
                        distance
                );

                if (top.size() < count) {
                    top.add(candidate);
                } else if (distance < top.peek().distance()) {
                    top.poll();
                    top.add(candidate);
                }
            }
        }

        List<SearchCandidate> candidates = new ArrayList<>(top);
        candidates.sort(Comparator.comparingDouble(SearchCandidate::distance));

        List<VectorResponse> result = new ArrayList<>();

        for (SearchCandidate candidate : candidates) {
            VectorObject object = candidate.object();

            result.add(new VectorResponse(
                    candidate.id(),
                    object.getVector(),
                    object.getUrl()
            ));
        }

        return result;
    }

    private void validateSearchRequest(SearchRequest request) { //TODO
        if (request.vector() == null || request.vector().length == 0) {
            throw new IllegalArgumentException("vector is required");
        }

        if (request.count() != null && request.count() <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
    }

    private double euclideanDistance(float[] a, float[] b) {
        double sum = 0.0;

        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }

        return Math.sqrt(sum);
    }
    private record SearchCandidate(
            Long id,
            VectorObject object,
            double distance
    ) {
    }
}
