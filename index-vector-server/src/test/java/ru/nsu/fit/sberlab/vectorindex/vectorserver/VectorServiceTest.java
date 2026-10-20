package ru.nsu.fit.sberlab.vectorindex.vectorserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import ru.nsu.fit.sberlab.vectorindex.common.dto.Neighbor;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchHit;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchResponse;

/** Совместимый {@code /vectors/search}: прежнее тело, статус — в заголовках. */
class VectorServiceTest {

    @Test
    void compatibleSearchKeepsTheBodyAndExposesStatusInHeaders() {
        SearchResponse response = new SearchResponse();
        response.results = List.of(new SearchHit(7, 0.25, "https://vectors/7", "m"));
        response.partial = true;
        response.timedOut = false;
        response.activePartitions = 31;
        response.totalPartitions = 32;

        ResponseEntity<List<Neighbor>> entity = VectorService.compatible(response);

        assertThat(entity.getBody()).containsExactly(new Neighbor(7, 0.25, "https://vectors/7", "m"));
        assertThat(entity.getHeaders().getFirst("X-Vindex-Partial")).isEqualTo("true");
        assertThat(entity.getHeaders().getFirst("X-Vindex-Timed-Out")).isEqualTo("false");
        assertThat(entity.getHeaders().getFirst("X-Vindex-Searched-Partitions")).isEqualTo("31");
        assertThat(entity.getHeaders().getFirst("X-Vindex-Expected-Partitions")).isEqualTo("32");
    }
}
