package ru.nsu.fit.sberlab.vectorindex.node.search;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import ru.nsu.fit.sberlab.vectorindex.common.ScoredVector;
import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchHit;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchResponse;
import ru.nsu.fit.sberlab.vectorindex.core.Budget;
import ru.nsu.fit.sberlab.vectorindex.core.NanoClock;

/**
 * Гидрация демо-ответа (url, metadata) — отдельный этап после поиска, в том же бюджете:
 * не начинается после его исчерпания, ожидание каждого батча ограничено остатком.
 * Удалённые к моменту гидрации ключи пропускаются, следующие батчи добирают top-k.
 * Документы — текущие, а не той версии, по которой посчитано расстояние (источник версий не даёт).
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

    public SearchResponse respond(DistributedSearchResult result, int k, Budget budget, long startNanos) {
        boolean timedOut = result.timedOut();
        List<SearchHit> hits = new ArrayList<>(Math.min(k, result.hits().size()));
        List<ScoredVector> ordered = result.hits();
        int i = 0;
        while (i < ordered.size() && hits.size() < k) {
            if (budget.exhausted()) {
                timedOut = true;
                break;
            }
            int end = Math.min(ordered.size(), i + BATCH);
            Set<Long> ids = new HashSet<>();
            for (int j = i; j < end; j++) {
                ids.add(ordered.get(j).id());
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
                ScoredVector candidate = ordered.get(j);
                VectorObject document = loaded == null ? null : loaded.get(candidate.id());
                if (document != null) {
                    hits.add(new SearchHit(candidate.id(), candidate.distance(), document.getUrl(), document.getMetadata()));
                }
            }
            i = end;
        }

        SearchResponse response = new SearchResponse();
        response.results = hits;
        response.tookMs = TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - startNanos);
        response.respondedNodes = result.respondedNodes();
        response.totalNodes = result.totalNodes();
        response.activePartitions = result.searchedPartitions().length;
        response.totalPartitions = result.expectedPartitions();
        response.searchedPartitions = result.searchedPartitions();
        response.failedPartitions = new ArrayList<>(result.failedPartitions());
        response.knownGapPartitions = result.knownGapPartitions();
        response.duplicateKeys = result.duplicateKeys();
        response.timedOut = timedOut;
        response.partial = result.partial() || timedOut;
        return response;
    }
}
