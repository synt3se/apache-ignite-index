package com.github.synt3se.gateway.web;

import com.github.synt3se.gateway.client.ClipClient;
import com.github.synt3se.gateway.client.IndexClient;
import com.github.synt3se.gateway.web.exceptions.ImageDownloadException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
public class GatewayController {

    private final ClipClient clipClient;
    private final IndexClient indexClient;

    public GatewayController(ClipClient clipClient, IndexClient indexClient) {
        this.clipClient = clipClient;
        this.indexClient = indexClient;
    }

    @PostMapping("/images")
    public Map<String, Object> addImage(@RequestBody Dto.AddImageUrlRequest request) throws IOException {
        byte[] imageBytes = downloadImage(request.url());

        float[] vector = clipClient.embedImage(imageBytes, request.url());
        Dto.VectorResponse response = indexClient.add(vector, request.url(), request.metadata());
        return Map.of("id", response.id(), "dim", vector.length, "url", request.url());
    }

    @PostMapping("/search/image")
    public List<Dto.Neighbor> searchImage(@RequestBody Dto.SearchImageUrlRequest request) throws IOException {
        byte[] imageBytes = downloadImage(request.url());

        float[] vector = clipClient.embedImage(imageBytes, request.url());
        return indexClient.search(vector, request.count() != null ? request.count() : 5);
    }

    @PostMapping(value = "/search/image/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<Dto.Neighbor> searchImageFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "count", required = false) Integer count) throws IOException {

        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }

        byte[] imageBytes = file.getBytes();

        float[] vector = clipClient.embedImage(imageBytes, file.getOriginalFilename());
        return indexClient.search(vector, count != null ? count : 5);
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

    @PostMapping("/images/add")
    public Dto.VectorResponse addVector(@RequestBody Dto.AddImageUrlRequest request) {
        String url = request.url();
        byte[] imageBytes = downloadImage(url);
        float[] vector = clipClient.embedImage(imageBytes, url);
        return indexClient.add(vector, url, null);
    }

    private byte[] downloadImage(String url) {
        RestTemplate restTemplate = new RestTemplate();
        byte[] imageBytes;
        try {
            imageBytes = restTemplate.getForObject(url, byte[].class);
        } catch (Exception ex) {
            throw new ImageDownloadException("Failed to download image from external host", url, ex);
        }
        if (imageBytes == null) {
            throw new ImageDownloadException("Failed to download image: host returned empty body", url, null);
        }
        return imageBytes;
    }
}
