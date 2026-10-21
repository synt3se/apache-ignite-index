package ru.nsu.fit.sberlab.vectorindex.vectorserver.search;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexHit;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchHit;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchResponse;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchResponse;
import ru.nsu.fit.sberlab.vectorindex.core.Budget;
import ru.nsu.fit.sberlab.vectorindex.core.NanoClock;
import ru.nsu.fit.sberlab.vectorindex.node.search.Awaiter;

/**
 * Гидрация демо-ответа (url, metadata) в прикладном слое — отдельный этап после поиска, в том
 * же бюджете: не начинается после его исчерпания, ожидание каждого батча ограничено остатком.
 * Удалённые к моменту гидрации ключи пропускаются, следующие батчи добирают top-k из
 * переданных кандидатов (top-k ответа индекса). Поэтому прикладной поиск возвращает <b>до k
 * существующих документов</b>: документ, удалённый после индексного поиска, уменьшает число
 * результатов, но не делает ответ неполным — статус индексного поиска сохраняется как есть.
 * Документы — текущие, а не той версии, по которой посчитано расстояние (источник версий не даёт).
 * Демо-кэш хранит ключи {@code Long}; другие ключи пропускаются.
 */
public final class DemoHydrator {

    static final int BATCH = 256;

    private final DocumentStore store;
    private final Awaiter awaiter;
    private final NanoClock clock;

    public DemoHydrator(DocumentStore store, Awaiter awaiter, NanoClock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.awaiter = Objects.requireNonNull(awaiter, "awaiter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SearchResponse respond(VectorSearchResponse found, int k, Budget budget, long startNanos) {
        boolean timedOut = found.timedOut;
        List<IndexHit> ordered = found.hits == null ? List.of() : found.hits;
        List<SearchHit> hits = new ArrayList<>(Math.min(k, ordered.size()));
        int i = 0;
        while (i < ordered.size() && hits.size() < k) {
            if (budget.exhausted()) {
                timedOut = true;
                break;
            }
            int end = Math.min(ordered.size(), i + BATCH);
            Set<Long> ids = new HashSet<>();
            for (int j = i; j < end; j++) {
                if (ordered.get(j).key instanceof Long id) {
                    ids.add(id);
                }
            }
            Map<Long, VectorObject> loaded;
            try {
                loaded = awaiter.await(store.getAll(ids), budget.remainingNanos());
            } catch (TimeoutException timeout) {
                timedOut = true;
                break;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                timedOut = true;
                break;
            } catch (ExecutionException failure) {
                throw new IllegalStateException("hydration failed", failure.getCause());
            }
            for (int j = i; j < end && hits.size() < k; j++) {
                IndexHit candidate = ordered.get(j);
                VectorObject document = loaded == null || !(candidate.key instanceof Long id) ? null : loaded.get(id);
                if (document != null) {
                    hits.add(new SearchHit((Long) candidate.key, candidate.distance, document.getUrl(),
                            document.getMetadata()));
                }
            }
            i = end;
        }

        SearchResponse response = new SearchResponse();
        response.results = hits;
        response.tookMs = TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - startNanos);
        response.respondedNodes = found.respondedNodes;
        response.totalNodes = found.totalNodes;
        response.activePartitions = found.searchedPartitions == null ? 0 : found.searchedPartitions.length;
        response.totalPartitions = found.expectedPartitions;
        response.searchedPartitions = found.searchedPartitions;
        response.failedPartitions = found.failedPartitions == null ? new ArrayList<>() : new ArrayList<>(found.failedPartitions);
        response.knownGapPartitions = found.knownGapPartitions;
        response.duplicateKeys = found.duplicateKeys;
        response.timedOut = timedOut;
        response.partial = found.partial() || timedOut;
        return response;
    }
}
