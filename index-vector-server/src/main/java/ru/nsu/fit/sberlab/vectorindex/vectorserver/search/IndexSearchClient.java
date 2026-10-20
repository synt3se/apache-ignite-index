package ru.nsu.fit.sberlab.vectorindex.vectorserver.search;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import ru.nsu.fit.sberlab.vectorindex.common.dto.Incompleteness;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchResponse;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchRequest;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchResponse;
import ru.nsu.fit.sberlab.vectorindex.core.Budget;
import ru.nsu.fit.sberlab.vectorindex.core.NanoClock;

/**
 * Поиск из прикладного слоя. Бюджет начинается здесь, на сервере, и ограничивает всё: ожидание
 * вызова сервиса через тонкий клиент (таймаут вызова — остаток), повтор после отказа
 * координатора (только если остаток есть) и гидрацию. Остаток передаётся сервису как
 * {@code timeoutMs}: передача остатка узлу сама по себе не ограничивает блокирующий вызов.
 */
public final class IndexSearchClient {

    /** Вызов сервиса поиска, ожидание которого не дольше {@code timeoutMs}. */
    @FunctionalInterface
    public interface Service {
        VectorSearchResponse search(VectorSearchRequest request, long timeoutMs);
    }

    private final Service service;
    private final DemoHydrator hydrator;
    private final NanoClock clock;
    private final long defaultTimeoutNanos;

    public IndexSearchClient(Service service, DemoHydrator hydrator, NanoClock clock, long defaultTimeoutNanos) {
        this.service = Objects.requireNonNull(service, "service");
        this.hydrator = Objects.requireNonNull(hydrator, "hydrator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.defaultTimeoutNanos = defaultTimeoutNanos;
    }

    /** Поиск без документов; {@code timeoutMs} запроса — бюджет, иначе значение сервера. */
    public VectorSearchResponse search(VectorSearchRequest request) {
        long timeout = request.timeoutMs > 0 ? TimeUnit.MILLISECONDS.toNanos(request.timeoutMs) : defaultTimeoutNanos;
        return search(request, Budget.remaining(clock, timeout));
    }

    /**
     * Поиск с документами демо-кэша (совместимость {@code /vectors/search*}): тот же бюджет
     * ограничивает вызов сервиса и гидрацию; гидрация не начинается после его исчерпания.
     *
     * @throws IndexRequestException неизвестный индекс или неверный запрос
     */
    public SearchResponse searchWithDocuments(String indexId, float[] vector, int k) {
        long start = clock.nanoTime();
        Budget budget = Budget.remaining(clock, defaultTimeoutNanos);
        VectorSearchResponse found = search(new VectorSearchRequest(indexId, vector, k), budget);
        if (found.error != null) {
            throw new IndexRequestException(found.error, found.message);
        }
        return hydrator.respond(found, k, budget, start);
    }

    VectorSearchResponse search(VectorSearchRequest request, Budget budget) {
        RuntimeException failure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(budget.remainingNanos());
            if (remainingMs <= 0) {
                break;
            }
            request.timeoutMs = remainingMs;
            try {
                return service.search(request, remainingMs);
            } catch (RuntimeException coordinatorUnavailable) {
                failure = coordinatorUnavailable; // повтор на другом узле — только в остатке бюджета
            }
        }
        if (failure != null && !budget.exhausted()) {
            throw failure;
        }
        return timedOut(request);
    }

    /** Бюджет исчерпан до ответа сервиса: результатов нет, причина — таймаут. */
    static VectorSearchResponse timedOut(VectorSearchRequest request) {
        VectorSearchResponse response = new VectorSearchResponse();
        response.indexId = request.indexId;
        response.status = request.allowPartial ? SearchStatus.PARTIAL : SearchStatus.FAILED;
        response.incomplete = new java.util.ArrayList<>(List.of(Incompleteness.TIMED_OUT));
        response.timedOut = true;
        return response;
    }
}
