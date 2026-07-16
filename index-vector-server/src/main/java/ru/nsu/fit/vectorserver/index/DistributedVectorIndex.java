package ru.nsu.fit.vectorserver.index;

import org.apache.ignite.cache.query.QueryCursor;
import org.apache.ignite.cache.query.ScanQuery;
import org.apache.ignite.client.ClientCache;
import org.apache.ignite.client.IgniteClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ru.nsu.fit.vector.common.ScoredVector;
import ru.nsu.fit.vector.common.VectorObject;
import ru.nsu.fit.vector.common.dto.AddRequest;
import ru.nsu.fit.vector.common.dto.ClusterStats;
import ru.nsu.fit.vector.common.dto.Neighbor;
import ru.nsu.fit.vector.common.dto.NodeStats;
import ru.nsu.fit.vector.node.compute.ClearVectorTask;
import ru.nsu.fit.vector.node.compute.SearchVectorTask;
import ru.nsu.fit.vector.node.compute.StatsTask;
import java.util.HashMap;
import java.util.Map;
import ru.nsu.fit.vector.node.compute.RebuildIndexesTask;

import javax.cache.Cache;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

@Primary
@Component
public class DistributedVectorIndex implements Index {
    private final IgniteClient igniteClient;
    private final ClientCache<Long, VectorObject> cache;
    private final int dimension;
    private static final int LOAD_BATCH_SIZE = 5_000;

    public DistributedVectorIndex(
            IgniteClient igniteClient,
            @Value("${ignite.cache.name:vectors}") String cacheName,
            @Value("${vector.dimension}") int dimension
    ) {
        this.igniteClient = igniteClient;
        this.cache = igniteClient.getOrCreateCache(cacheName);
        this.dimension = dimension;
    }

    private void validateVector(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("Vector must not be null");
        }

        if (vector.length != dimension) {
            throw new IllegalArgumentException(
                    "Incorrect vector dimension: "
                            + vector.length
                            + ", required: "
                            + dimension
            );
        }
    }

    @Override
    public void add(long id, AddRequest request) {
        validateVector(request.vector());
        cache.put(id, new VectorObject(request.vector(), request.url(), request.metadata()));
        // индекс — производная кэша: слушатель владельца сам добавит вектор
    }

    @Override
    public VectorObject get(long id) {
        return cache.get(id);
    }

    @Override
    public boolean delete(long id) {
        return cache.remove(id);   // слушатель владельца сам поставит tombstone
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
        validateVector(queryVector);

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

    @Override
    public void save(String path) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(path))) {
            bw.write("id,url,embedding");
            bw.newLine();

            long savedCount = 0;

            try (QueryCursor<Cache.Entry<Long, VectorObject>> cursor = cache.query(new ScanQuery<>())) {
                for (Cache.Entry<Long, VectorObject> entry : cursor) {
                    Long id = entry.getKey();
                    VectorObject obj = entry.getValue();

                    if (obj == null || obj.getVector() == null) {
                        continue;
                    }

                    StringBuilder vectorBuilder = new StringBuilder("[");
                    float[] v = obj.getVector();
                    for (int i = 0; i < v.length; i++) {
                        vectorBuilder.append(v[i]);
                        if (i < v.length - 1) {
                            vectorBuilder.append(", ");
                        }
                    }
                    vectorBuilder.append("]");

                    String csvLine = String.format("%d,%s,\"%s\"", id, obj.getUrl(), vectorBuilder.toString());

                    bw.write(csvLine);
                    bw.newLine();

                    savedCount++;
                    if (savedCount % 50000 == 0) {
                        System.out.println("Export from cache: " + savedCount + " lines...");
                    }
                }
            }

            System.out.println("=== CSV export completed! Lines: " + savedCount + " ===");

        } catch (IOException e) {
            throw new RuntimeException("CSV export error: " + path, e);
        }
    }
    @Override
    public long load(String path) {
        long maxId = 0;
        long importedCount = 0;
        Map<Long, VectorObject> batch = new HashMap<>(LOAD_BATCH_SIZE);

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            br.readLine();

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;

                int firstComma = line.indexOf(';');
                int secondComma = line.indexOf(';', firstComma + 1);
                if (firstComma == -1 || secondComma == -1) continue;

                long id = Long.parseLong(line.substring(0, firstComma));
                String url = line.substring(firstComma + 1, secondComma);
                String vectorStr = line.substring(secondComma + 1)
                        .replace("\"", "")
                        .replace("[", "")
                        .replace("]", "")
                        .trim();

                String[] tokens = vectorStr.split(",\\s*");
                float[] vector = new float[tokens.length];

                for (int i = 0; i < tokens.length; i++) vector[i] = Float.parseFloat(tokens[i]);

                if (vector.length != dimension) {
                    throw new IllegalArgumentException(
                            "Incorrect vector dimension for id " + id +
                                    ": " + vector.length + ", required: " + dimension
                    );
                }

                batch.put(id, new VectorObject(vector, url, null));
                importedCount++;
                maxId = Math.max(maxId, id);

                if (batch.size() >= LOAD_BATCH_SIZE) flushLoadBatch(batch);
                if (importedCount % 50_000 == 0) {
                    System.out.println("Import from CSV: " + importedCount + " lines...");
                }
            }

            flushLoadBatch(batch);
            rebuildIndexes();

            System.out.println("=== Import completed! Lines: " + importedCount + " ===");
            return maxId;
        } catch (IOException e) {
            throw new RuntimeException("CSV load error: " + path, e);
        }
    }

    private void flushLoadBatch(Map<Long, VectorObject> batch) {
        if (batch.isEmpty()) return;
        cache.putAll(batch);
        batch.clear();
    }

    @Override
    public void addAll(Map<Long, VectorObject> vectors) {
        //TODO Тут происходит проверка всех векторов мб потом заменить на что-то получше
        for (Map.Entry<Long, VectorObject> entry : vectors.entrySet()) {
            VectorObject object = entry.getValue();

            if (object == null) {
                throw new IllegalArgumentException(
                        "VectorObject must not be null for id " + entry.getKey()
                );
            }

            try {
                validateVector(object.getVector());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Incorrect vector for id " + entry.getKey() + ": " + e.getMessage(), e
                );
            }
        }


        cache.putAll(vectors);
    }

    @Override
    public void rebuild() {
        try {
            igniteClient.compute().execute(RebuildIndexesTask.class.getName(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Index rebuild was interrupted", e);
        }
    }

    private void rebuildIndexes() {
        try {
            igniteClient.compute().execute(RebuildIndexesTask.class.getName(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Index rebuild was interrupted", e);
        }
    }

    @Override
    public ClusterStats stats() {
        List<NodeStats> nodes;
        try {
            nodes = igniteClient.compute().execute(StatsTask.class.getName(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Stats task interrupted", e);
        }
        ClusterStats cs = new ClusterStats();
        cs.nodes = nodes;
        cs.serverNodes = nodes.size();
        long live = 0;
        for (NodeStats n : nodes) live += n.liveVectors;
        cs.totalLiveVectors = live;
        return cs;
    }

}