package com.github.synt3se.gateway.client;

import com.github.synt3se.gateway.web.Dto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class IndexClient {

    private final RestClient http;

    public IndexClient(@Value("${index.url}") String baseUrl, RestClient.Builder builder) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    public void add(long id, float[] vector, String url, String metadata) {
        http.post()
                .uri("/vectors")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Dto.IndexAddRequest(id, vector, url, metadata))
                .retrieve()
                .toBodilessEntity();
    }

    public void delete(long id) {
        http.delete()
                .uri("/vectors/" + id)
                .retrieve()
                .toBodilessEntity();
    }

    public Dto.VectorResponse get(long id) {
        return http.get()
                .uri("/vectors/" + id)
                .retrieve()
                .body(Dto.VectorResponse.class);
    }

    public List<Dto.Neighbor> search(float[] vector, int count) {
        return http.post()
                .uri("/vectors/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Dto.IndexSearchRequest(vector, count))
                .retrieve()
                .body(new ParameterizedTypeReference<List<Dto.Neighbor>>() {});
    }

}
