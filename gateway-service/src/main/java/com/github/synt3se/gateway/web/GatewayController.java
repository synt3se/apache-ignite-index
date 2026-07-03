package com.github.synt3se.gateway.web;

import com.github.synt3se.gateway.client.ClipClient;
import com.github.synt3se.gateway.client.IndexClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@RestController
public class GatewayController {

    private final ClipClient clipClient;
    private final IndexClient indexClient;

    private final AtomicLong idGen = new AtomicLong(System.currentTimeMillis());

    public GatewayController(ClipClient clipClient, IndexClient indexClient) {
        this.clipClient = clipClient;
        this.indexClient = indexClient;
    }

    @PostMapping("/images")
    public Map<String, Object> addImage(@RequestBody Dto.AddImageUrlRequest request) throws IOException {
        RestTemplate restTemplate = new RestTemplate();

        byte[] imageBytes = restTemplate.getForObject(
                request.url(),
                byte[].class
        );

        if (imageBytes == null) {
            throw new RuntimeException("Failed to download image");
        }

        float[] vector = clipClient.embedImage(imageBytes, request.url());
        long id = idGen.incrementAndGet();
        indexClient.add(id, vector, request.url(), request.metadata());
        return Map.of("id", id, "dim", vector.length, "url", request.url());
    }

    @PostMapping("/search/image")
    public List<Dto.Neighbor> searchImage(@RequestBody Dto.SearchImageUrlRequest request) throws IOException {
        RestTemplate restTemplate = new RestTemplate();

        byte[] imageBytes = restTemplate.getForObject(
                request.url(),
                byte[].class
        );

        if (imageBytes == null) {
            throw new RuntimeException("Failed to download image");
        }

        float[] vector = clipClient.embedImage(imageBytes, request.url());
        return indexClient.search(vector, request.count() != null ? request.count() : 5);
    }

    @PostMapping("/search/text")
    public List<Dto.Neighbor> searchText(@RequestBody Dto.TextSearchRequest request) {
        float[] vec = clipClient.embedText(request.text());
        return indexClient.search(vec, request.count() != null ? request.count() : 5);
    }

    @PostMapping("/search/vector")
    public List<Dto.Neighbor> searchVector(@RequestBody Dto.SearchVectorRequest request) {
        return indexClient.search(request.vector(), request.count() != null ? request.count() : 5);
    }

    @GetMapping("/images/{id}")
    public Dto.VectorResponse getImage(@PathVariable long id) {
        return indexClient.get(id);
    }

    @DeleteMapping("/images/{id}")
    public ResponseEntity<Void> deleteImage(@PathVariable long id) {
        indexClient.delete(id);
        return ResponseEntity.noContent().build();
    }

}
