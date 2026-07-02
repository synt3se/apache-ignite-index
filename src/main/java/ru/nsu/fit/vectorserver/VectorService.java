package ru.nsu.fit.vectorserver;

import org.apache.ignite.client.ClientCache;
import org.apache.ignite.client.IgniteClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.nsu.fit.vectorserver.dto.AddRequest;

import java.util.Map;


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

    private void validateSaveRequest(AddRequest request) {
        if (request.id() == null) {
            throw new IllegalArgumentException("id is required");
        }

        if (request.url() == null || request.url().isBlank()) {
            throw new IllegalArgumentException("url is required");
        }

        if (request.vector() == null || request.vector().length == 0) {
            throw new IllegalArgumentException("vector is required");
        }
    }
}
