package ru.nsu.fit.sberlab.vectorindex.vectorserver.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import ru.nsu.fit.sberlab.vectorindex.common.dto.Incompleteness;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexHit;
import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexOperationResult;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchError;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchStatus;
import ru.nsu.fit.sberlab.vectorindex.common.dto.VectorSearchResponse;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.FakeIndex;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.exception.ResourceNotFoundException;

/** REST индексов без Spring-контекста: коды ответа, тело и типы ключей в JSON. */
class IndexControllerTest {

    private final FakeIndex index = new FakeIndex();
    private final IndexController controller = new IndexController(index);

    @Test
    void completeAndPartialAnswersAre200AndCarryTheirStatus() {
        index.response = response(SearchStatus.PARTIAL);
        index.response.incomplete = new ArrayList<>(List.of(Incompleteness.KNOWN_GAP));
        index.response.knownGapPartitions = new int[]{3};

        ResponseEntity<IndexSearchView> entity = controller.search("images", body(true));

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getBody().status()).isEqualTo(SearchStatus.PARTIAL);
        assertThat(entity.getBody().incomplete()).containsExactly(Incompleteness.KNOWN_GAP);
        assertThat(entity.getBody().knownGapPartitions()).containsExactly(3);
        assertThat(index.lastRequest.indexId).isEqualTo("images");
        assertThat(index.lastRequest.params).containsEntry("ef", 64);
    }

    @Test
    void strictIncompleteAnswerIs503AndErrorsAre400Or404() {
        index.response = response(SearchStatus.FAILED);
        index.response.hits = new ArrayList<>();
        ResponseEntity<IndexSearchView> strict = controller.search("images", body(false));
        assertThat(strict.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(index.lastRequest.allowPartial).isFalse();
        assertThat(strict.getBody().hits()).isEmpty();

        index.response = VectorSearchResponse.failed("images", SearchError.INVALID_REQUEST, "bad dimension");
        assertThat(controller.search("images", body(true)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        index.response = VectorSearchResponse.failed("nope", SearchError.UNKNOWN_INDEX, "unknown index");
        assertThat(controller.search("nope", body(true)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void keysKeepTheirTypeInJson() throws Exception {
        UUID uuid = new UUID(1, 2);
        index.response = response(SearchStatus.COMPLETE);
        index.response.hits = new ArrayList<>(List.of(new IndexHit(42L, 0.1), new IndexHit("42", 0.2),
                new IndexHit(uuid, 0.3)));

        JsonNode json = new ObjectMapper().valueToTree(controller.search("images", body(true)).getBody());

        JsonNode hits = json.get("hits");
        assertThat(hits.get(0).get("key").isNumber()).isTrue();
        assertThat(hits.get(0).get("keyType").asText()).isEqualTo("LONG");
        assertThat(hits.get(1).get("key").asText()).isEqualTo("42");
        assertThat(hits.get(1).get("keyType").asText()).as("a string that looks like a number").isEqualTo("STRING");
        assertThat(hits.get(2).get("key").asText()).isEqualTo(uuid.toString());
        assertThat(hits.get(2).get("keyType").asText()).isEqualTo("UUID");
    }

    @Test
    void operationsReportPerNodeResultsAndAreConfirmedOnlyByAllServers() {
        IndexOperationResult partial = new IndexOperationResult();
        partial.nodes.add(new IndexOperationResult.NodeOutcome("a", IndexOperationResult.Outcome.DONE, null));
        partial.nodes.add(new IndexOperationResult.NodeOutcome("b", IndexOperationResult.Outcome.NO_RESPONSE, null));
        index.operation = partial;
        assertThat(controller.unload("images").getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(controller.unload("images").getBody().nodes).hasSize(2);

        partial.confirmed = true;
        assertThat(controller.rebuild("images").getStatusCode()).isEqualTo(HttpStatus.OK);

        index.operation = null;
        assertThatThrownBy(() -> controller.unload("nope")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> controller.status("nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void truncateIsRefusedForASharedCacheAndBarriersOfUnknownIndexesAre404() {
        IndexOperationResult refused = new IndexOperationResult();
        refused.refused = true;
        refused.message = "cache 'docs' is shared with indexes [body]";
        index.operation = refused;
        assertThat(controller.truncate("title").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        IndexOperationResult confirmed = new IndexOperationResult();
        confirmed.confirmed = true;
        index.operation = confirmed;
        assertThat(controller.truncate("bench").getStatusCode()).isEqualTo(HttpStatus.OK);
        confirmed.confirmed = false;
        assertThat(controller.truncate("bench").getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        ru.nsu.fit.sberlab.vectorindex.common.dto.IndexBarrier barrier = new ru.nsu.fit.sberlab.vectorindex.common.dto.IndexBarrier();
        barrier.state = ru.nsu.fit.sberlab.vectorindex.common.dto.IndexBarrier.State.STARTED;
        index.barrier = barrier;
        assertThat(controller.beginBarrier("bench", true).state)
                .isEqualTo(ru.nsu.fit.sberlab.vectorindex.common.dto.IndexBarrier.State.STARTED);
        index.barrier = null;
        assertThatThrownBy(() -> controller.barrierProgress("nope", "b")).isInstanceOf(ResourceNotFoundException.class);
    }

    private static IndexSearchBody body(boolean allowPartial) {
        return new IndexSearchBody(new float[]{1, 0}, 3, false, Map.of("ef", 64), allowPartial, 500L);
    }

    private static VectorSearchResponse response(SearchStatus status) {
        VectorSearchResponse response = new VectorSearchResponse();
        response.indexId = "images";
        response.status = status;
        response.hits = new ArrayList<>(List.of(new IndexHit(1L, 0.1)));
        response.expectedPartitions = 4;
        response.searchedPartitions = new int[]{0, 1, 2, 3};
        return response;
    }
}
