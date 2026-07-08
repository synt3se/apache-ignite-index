package ru.nsu.fit.vectorserver.index;

import org.apache.ignite.client.ClientCache;
import org.apache.ignite.client.IgniteClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ru.nsu.fit.vector.common.ScoredVector;
import ru.nsu.fit.vector.common.VectorObject;
import ru.nsu.fit.vector.common.dto.AddRequest;
import ru.nsu.fit.vector.common.dto.Neighbor;
import ru.nsu.fit.vector.node.compute.AddVectorTask;
import ru.nsu.fit.vector.node.compute.ClearVectorTask;
import ru.nsu.fit.vector.node.compute.DeleteVectorTask;
import ru.nsu.fit.vector.node.compute.SearchVectorTask;

import java.util.ArrayList;
import java.util.List;

@Primary
@Component
public class DistributedVectorIndex implements Index {
    private final IgniteClient igniteClient;
    private final ClientCache<Long, VectorObject> cache;
    private final int dimension;

    public DistributedVectorIndex(
            IgniteClient igniteClient,
            @Value("${ignite.cache.name:vectors}") String cacheName,
            @Value("${vector.dimension}") int dimension
    ) {
        this.igniteClient = igniteClient;
        this.cache = igniteClient.getOrCreateCache(cacheName);
        this.dimension = dimension;
    }

    @Override
    public void add(long id, AddRequest request) {
        validateSaveRequest(request);

        VectorObject object = new VectorObject(
                request.vector(),
                request.url(),
                request.metadata()
        );

        try {
            cache.put(id, object);

            igniteClient.compute().execute(
                    AddVectorTask.class.getName(),
                    new AddVectorTask.Arg(id, request.vector())
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cache.remove(id);
            throw new RuntimeException("Add vector task was interrupted", e);
        } catch (RuntimeException e) {
            cache.remove(id);
            throw e;
        }
    }

    @Override
    public VectorObject get(long id) {
        return cache.get(id);
    }

    @Override
    public boolean delete(long id) {
        boolean removed = cache.remove(id);

        if (removed) {
            try {
                igniteClient.compute().execute(
                        DeleteVectorTask.class.getName(),
                        new DeleteVectorTask.Arg(id)
                );
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Delete vector task was interrupted", e);
            }
        }

        return removed;
    }

    @Override
    public void clear() {
        cache.clear();

        try {
            igniteClient.compute().execute(
                    ClearVectorTask.class.getName(),
                    null
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Clear vector task was interrupted", e);
        }
    }

    @Override
    public List<Neighbor> search(float[] queryVector, int count) {
        validateSearchRequest(queryVector, count);

        List<ScoredVector> scoredVectors;

        try {
            scoredVectors = igniteClient.compute().execute(
                    SearchVectorTask.class.getName(),
                    new SearchVectorTask.Arg(queryVector, count)
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Search vector task was interrupted", e);
        }

        List<Neighbor> result = new ArrayList<>();

        for (ScoredVector scoredVector : scoredVectors) {
            VectorObject object = cache.get(scoredVector.id());

            if (object == null) {
                continue;
            }

            result.add(new Neighbor(
                    scoredVector.id(),
                    scoredVector.distance(),
                    object.getUrl(),
                    object.getMetadata()
            ));
        }

        return result;
    }

    private void validateSaveRequest(AddRequest request) {
        if (request.url() == null || request.url().isBlank()) {
            throw new IllegalArgumentException("url is required");
        }

        if (request.vector() == null) {
            throw new IllegalArgumentException("vector is required");
        }

        if (request.vector().length != dimension) {
            throw new IllegalArgumentException(
                    "incorrect vector dimension: " + request.vector().length
                            + ", required: " + dimension
            );
        }
    }

    private void validateSearchRequest(float[] vector, int count) {
        if (vector == null) {
            throw new IllegalArgumentException("vector is required");
        }

        if (vector.length != dimension) {
            throw new IllegalArgumentException(
                    "incorrect vector dimension: " + vector.length
                            + ", required: " + dimension
            );
        }

        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
    }
}