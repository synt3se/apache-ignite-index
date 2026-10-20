package ru.nsu.fit.sberlab.vectorindex.vectorserver.index;

import org.apache.ignite.cache.query.QueryCursor;
import org.apache.ignite.cache.query.ScanQuery;
import org.apache.ignite.client.ClientCache;
import org.apache.ignite.client.ClientException;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.client.IgniteClientFuture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;
import ru.nsu.fit.sberlab.vectorindex.common.dto.*;
import ru.nsu.fit.sberlab.vectorindex.node.compute.ClearVectorTask;
import ru.nsu.fit.sberlab.vectorindex.node.compute.StatsTask;
import ru.nsu.fit.sberlab.vectorindex.node.service.SearchAggregationService;
import ru.nsu.fit.sberlab.vectorindex.core.NanoClock;
import ru.nsu.fit.sberlab.vectorindex.node.search.Awaiter;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.search.ClientDocumentStore;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.search.DemoHydrator;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.search.IndexSearchClient;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;
import ru.nsu.fit.sberlab.vectorindex.node.compute.RebuildIndexesTask;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import javax.cache.Cache;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.LongPredicate;

@Primary
@Component
public class DistributedVectorIndex implements Index {
    private final IgniteClient igniteClient;
    private final ClientCache<Long, VectorObject> cache;
    private final int dimension;
    private static final int LOAD_BATCH_SIZE = 5_000;
    private static final int PARSE_CHUNK = 20_000;
    private static final int MAX_IN_FLIGHT_BATCHES = 3;

    private final Counter searchTotal;
    private final Counter searchPartial;
    private final String defaultIndex;
    private final long adminTimeoutMs;
    private final IndexSearchClient searchClient;

    /** Для бенчмарк-раннеров вне Spring: индекс по умолчанию и таймауты сервера по умолчанию. */
    public DistributedVectorIndex(IgniteClient igniteClient, String cacheName, int dimension, MeterRegistry registry) {
        this(igniteClient, cacheName, dimension, "vectors", 2_000, 15_000, registry);
    }

    @Autowired
    public DistributedVectorIndex(
            IgniteClient igniteClient,
            @Value("${ignite.cache.name:vectors}") String cacheName,
            @Value("${vector.dimension}") int dimension,
            @Value("${vindex.default-index:vectors}") String defaultIndex,
            @Value("${vector.search.timeout-ms:2000}") long searchTimeoutMs,
            @Value("${vector.admin.timeout-ms:15000}") long adminTimeoutMs,
            MeterRegistry registry
    ) {
        this.igniteClient = igniteClient;
        this.cache = igniteClient.getOrCreateCache(cacheName);
        this.dimension = dimension;
        this.defaultIndex = defaultIndex;
        this.adminTimeoutMs = adminTimeoutMs;
        this.searchTotal = registry.counter("vindex.search.total");
        this.searchPartial = registry.counter("vindex.search.partial");
        // Гидрация — здесь, в прикладном слое; бюджет начинается на сервере и ограничивает вызов сервиса.
        this.searchClient = new IndexSearchClient(
                (request, timeoutMs) -> aggregator(timeoutMs).search(request),
                new DemoHydrator(new ClientDocumentStore(cache), Awaiter.BLOCKING, NanoClock.SYSTEM),
                NanoClock.SYSTEM, TimeUnit.MILLISECONDS.toNanos(searchTimeoutMs));
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

    /** Совместимость демо: список без статуса; статус — в {@link #searchFull}. */
    @Override
    public List<Neighbor> search(float[] queryVector, int count) {
        SearchResponse response = searchFull(queryVector, count);

        List<Neighbor> result = new ArrayList<>(response.results.size());
        for (SearchHit hit : response.results) {
            result.add(new Neighbor(hit.id, hit.distance, hit.url, hit.metadata));
        }
        return result;
    }

    @Override
    public void save(String path) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(path))) {
            bw.write("id;url;embedding;metadata");
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

                    String metadata = obj.getMetadata() != null ? obj.getMetadata() : "";

                    String csvLine = String.format("%d;%s;%s;%s",
                            id,
                            obj.getUrl(),
                            vectorBuilder.toString(),
                            metadata
                    );

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
        List<IgniteClientFuture<Void>> inFlight = new ArrayList<>();
        long t0 = System.nanoTime();
        long putWaitNanos = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            br.readLine();   // заголовок

            List<String> chunk = new ArrayList<>(PARSE_CHUNK);
            String line;
            boolean eof = false;
            while (!eof) {
                line = br.readLine();
                eof = (line == null);
                if (!eof) {
                    if (line.isBlank()) continue;
                    chunk.add(line);
                }
                if (chunk.size() >= PARSE_CHUNK || (eof && !chunk.isEmpty())) {
                    List<ParsedRow> rows = chunk.parallelStream()
                            .map(this::parseCsvLine)
                            .filter(Objects::nonNull)
                            .toList();
                    chunk.clear();

                    for (ParsedRow row : rows) {
                        batch.put(row.id(), row.object());
                        importedCount++;
                        maxId = Math.max(maxId, row.id());

                        if (batch.size() >= LOAD_BATCH_SIZE) {
                            inFlight.add(cache.putAllAsync(batch));
                            batch = new HashMap<>(LOAD_BATCH_SIZE);   // отправленную мапу НЕ переиспользуем
                            if (inFlight.size() >= MAX_IN_FLIGHT_BATCHES) {
                                putWaitNanos += awaitOldest(inFlight);
                            }
                        }
                    }
                    System.out.println("Import from CSV: " + importedCount + " lines...");
                }
            }

            if (!batch.isEmpty()) inFlight.add(cache.putAllAsync(batch));
            while (!inFlight.isEmpty()) putWaitNanos += awaitOldest(inFlight);

            long totalMs = (System.nanoTime() - t0) / 1_000_000;
            long putWaitMs = putWaitNanos / 1_000_000;
            System.out.println("=== Import completed! Lines: " + importedCount
                    + ", total=" + totalMs + " ms"
                    + ", putAll-wait=" + putWaitMs + " ms"
                    + ", parse+read=" + (totalMs - putWaitMs) + " ms ===");
            return maxId;
        } catch (IOException e) {
            throw new RuntimeException("CSV load error: " + path, e);
        } finally {
            rebuildIndexes();   // ВСЕГДА: снимает bulk-паузу даже если заливка упала
        }
    }

    private long awaitOldest(List<IgniteClientFuture<Void>> inFlight) {
        long waitT0 = System.nanoTime();
        try {
            inFlight.remove(0).get();
            return System.nanoTime() - waitT0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Bulk load interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Batch write failed", e.getCause());
        }
    }

    private record ParsedRow(long id, VectorObject object) {}

    private ParsedRow parseCsvLine(String line) {
        int firstSemicolon = line.indexOf(';');
        int secondSemicolon = line.indexOf(';', firstSemicolon + 1);
        int thirdSemicolon = line.indexOf(';', secondSemicolon + 1);
        if (firstSemicolon == -1 || secondSemicolon == -1 || thirdSemicolon == -1) return null;

        long id = Long.parseLong(line.substring(0, firstSemicolon));
        String url = line.substring(firstSemicolon + 1, secondSemicolon);
        String metadataStr = line.substring(thirdSemicolon + 1).trim();
        String metadata = metadataStr.isEmpty() ? null : metadataStr;

        float[] vector = parseVectorFast(line.substring(secondSemicolon + 1, thirdSemicolon));
        if (vector.length != dimension) {
            throw new IllegalArgumentException("Incorrect vector dimension for id " + id
                    + ": " + vector.length + ", required: " + dimension);
        }
        return new ParsedRow(id, new VectorObject(vector, url, metadata));
    }

    /** Ручной парс "[f, f, ...]" без regex: два прохода, ноль промежуточных массивов строк. */
    private float[] parseVectorFast(String s) {
        int start = s.indexOf('[') + 1;
        int end = s.lastIndexOf(']');
        if (end < 0) end = s.length();

        int count = 1;
        for (int i = start; i < end; i++) {
            if (s.charAt(i) == ',') count++;
        }
        float[] v = new float[count];
        int pos = start;
        for (int i = 0; i < count; i++) {
            int comma = s.indexOf(',', pos);
            if (comma < 0 || comma > end) comma = end;
            v[i] = Float.parseFloat(s.substring(pos, comma).trim());
            pos = comma + 1;
        }
        return v;
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
        long mem = 0;
        for (NodeStats n : nodes) {
            live += n.liveVectors;
            mem += n.indexMemoryEstimateBytes;
        }
        cs.totalLiveVectors = live;
        cs.totalIndexMemoryEstimateBytes = mem;
        return cs;
    }

    /** Прокси сервиса с ограничением ожидания вызова; тонкий клиент выбирает узел заново при каждом вызове. */
    private SearchAggregationService aggregator(long timeoutMs) {
        return igniteClient.services().serviceProxy(SearchAggregationService.NAME, SearchAggregationService.class,
                Math.max(1, timeoutMs));
    }

    /** Поиск с документами демо-кэша и полным статусом; индекс по умолчанию — {@code vindex.default-index}. */
    @Override
    public SearchResponse searchFull(float[] queryVector, int count) {
        validateVector(queryVector);
        SearchResponse response = searchClient.searchWithDocuments(defaultIndex, queryVector, count);
        searchTotal.increment();
        if (response.partial) {
            searchPartial.increment();
        }
        return response;
    }

    @Override
    public VectorSearchResponse search(VectorSearchRequest request) {
        VectorSearchResponse response = searchClient.search(request);
        searchTotal.increment();
        if (response.partial()) {
            searchPartial.increment();
        }
        return response;
    }

    @Override
    public List<IndexInfo> indexes() {
        return aggregator(adminTimeoutMs).indexes();
    }

    @Override
    public IndexStatus status(String indexId) {
        return aggregator(adminTimeoutMs).status(indexId);
    }

    @Override
    public IndexOperationResult unload(String indexId) {
        return aggregator(adminTimeoutMs).unload(indexId);
    }

    @Override
    public IndexOperationResult rebuild(String indexId) {
        return aggregator(adminTimeoutMs).rebuild(indexId);
    }
}