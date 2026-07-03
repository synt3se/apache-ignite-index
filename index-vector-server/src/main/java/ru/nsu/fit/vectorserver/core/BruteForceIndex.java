package ru.nsu.fit.vectorserver.core;

import org.apache.ignite.cache.query.QueryCursor;
import org.apache.ignite.cache.query.ScanQuery;
import org.apache.ignite.client.ClientCache;
import org.apache.ignite.client.IgniteClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.nsu.fit.vectorserver.VectorObject;
import ru.nsu.fit.vectorserver.dto.AddRequest;
import ru.nsu.fit.vectorserver.dto.Neighbor;

import javax.cache.Cache;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

@Component
public class BruteForceIndex implements Index {

    private final ClientCache<Long, VectorObject> cache;
    private final int dimension;

    public BruteForceIndex(IgniteClient igniteClient,
                         @Value("${ignite.cache.name}") String cacheName,
                         @Value("${vector.dimension}") int dimension
    ){
        this.cache = igniteClient.getOrCreateCache(cacheName);
        this.dimension = dimension;
    }

    public void add(AddRequest request) {
        validateSaveRequest(request);
        VectorObject object = new VectorObject(request.vector(), request.url(), request.metadata());

        cache.put(request.id(), object);
    }

    public VectorObject get(long id) {
        return cache.get(id);
    }

    public boolean delete(long id) {
        return cache.remove(id);
    }

    public List<Neighbor> search(float[] queryVector, int count) {
        validateSearchRequest(queryVector, count);

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
                } else {
                    assert top.peek() != null;
                    if (distance < top.peek().distance()) {
                        top.poll();
                        top.add(candidate);
                    }
                }
            }
        }

        List<SearchCandidate> candidates = new ArrayList<>(top);
        candidates.sort(Comparator.comparingDouble(SearchCandidate::distance));

        List<Neighbor> result = new ArrayList<>();

        for (SearchCandidate candidate : candidates) {
            VectorObject object = candidate.object();

            result.add(new Neighbor(
                    candidate.id(),
                    candidate.distance,
                    object.getUrl(),
                    object.getMetadata()
            ));
        }

        return result;
    }

    private void validateSaveRequest(AddRequest request) {
        if (request.id() == null || request.id() <= 0) {
            throw new IllegalArgumentException("id is required");
        }

        if (request.url() == null || request.url().isBlank()) {
            throw new IllegalArgumentException("url is required");
        }

        if (request.vector() == null) {
            throw new IllegalArgumentException("vector is required");
        }

        if (request.vector().length != dimension){
            throw new IllegalArgumentException("incorrect vector dimension (" +
                    request.vector().length + "), required: " + dimension);
        }
    }

    private void validateSearchRequest(float[] vector, int count) {
        if (vector == null) {
            throw new IllegalArgumentException("vector is required");
        }

        if (vector.length != dimension){
            throw new IllegalArgumentException("incorrect vector dimension (" +
                    vector.length + "), required: " + dimension);
        }

        if (count <= 0) {
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
    ) {}

}
