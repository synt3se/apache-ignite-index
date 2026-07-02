package com.github.synt3se.gateway.client;

import com.github.synt3se.gateway.web.Dto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ClipClient {

    private final RestClient http;

    public ClipClient(@Value("${clip.url}") String baseUrl, RestClient.Builder builder) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    public float[] embedImage(byte[] image, String filename) {
        ByteArrayResource part = new ByteArrayResource(image) {
            @Override
            public String getFilename() {
                return filename != null ? filename : "image";
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", part);

        Dto.EmbeddingResponse response = http.post()
                .uri("/embed/image")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(Dto.EmbeddingResponse.class);
        return response.vector();
    }

    public float[] embedText(String text) {
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Поле 'text' обязательно");
        }
        Dto.EmbeddingResponse response = http.post()
                .uri("/embed/text")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Dto.ClipTextRequest(text))
                .retrieve()
                .body(Dto.EmbeddingResponse.class);
        return response.vector();
    }

}
