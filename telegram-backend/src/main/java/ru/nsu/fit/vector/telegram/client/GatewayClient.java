package ru.nsu.fit.vector.telegram.client;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import ru.nsu.fit.vector.telegram.Dto;
import ru.nsu.fit.vector.telegram.Dto.Neighbor;
import ru.nsu.fit.vector.telegram.exception.GatewayException;
import ru.nsu.fit.vector.telegram.exception.ImageDownloadException;

import java.util.Map;

@Component
public class GatewayClient {
    private final WebClient webClient;

    public GatewayClient(WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<Neighbor[]> searchUrl(String imageUrl) {
        var requestBody = Map.of("url", imageUrl, "count", 5);
        return webClient.post()
                .uri("/search/image")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .bodyToMono(Neighbor[].class)
                .timeout(java.time.Duration.ofSeconds(30));
    }

    public Mono<Neighbor[]> searchTxt(String text) {
        var requestBody = Map.of("text", text, "count", 5);
        return webClient.post()
                .uri("/search/text")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .bodyToMono(Neighbor[].class)
                .timeout(java.time.Duration.ofSeconds(30));
    }

    public Mono<Neighbor[]> searchFile(String fileId, MultiValueMap<String, Object> body) {
        return webClient.post()
                .uri("/search/image/file")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .bodyToMono(Neighbor[].class)
                .timeout(java.time.Duration.ofSeconds(30));
    }

    public Mono<Dto.VectorResponse> getVectorById(long id) {
        return webClient.get()
                .uri("/images/" + id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .bodyToMono(Dto.VectorResponse.class);
    }

    public Mono<Dto.VectorResponse> addVector(String link) {
        var requestBody = Map.of("url", link);
        return webClient.post()
                .uri("/images/add")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .bodyToMono(Dto.VectorResponse.class)
                .timeout(java.time.Duration.ofSeconds(30));
    }

    public Mono<Dto.VectorResponse> deleteVector(long id) {
        return webClient.delete()
                .uri("/images/" + id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .bodyToMono(Dto.VectorResponse.class);
    }

    private Mono<? extends Throwable> mapError(ClientResponse clientResponse) {
        int statusCode = clientResponse.statusCode().value();
        return clientResponse.bodyToMono(Map.class)
                .flatMap(errorBody -> {
                    String errorSign = errorBody.get("error") != null ? errorBody.get("error").toString() : "";
                    String serverMessage = errorBody.get("message") != null ? errorBody.get("message").toString() : "Unknown error";

                    if ("image_download_error".equals(errorSign)) {
                        return Mono.<Throwable>error(new ImageDownloadException(serverMessage, "", null));
                    }
                    return Mono.<Throwable>error(new GatewayException(statusCode, serverMessage));
                })
                .switchIfEmpty(Mono.<Throwable>error(new GatewayException(statusCode, "Неизвестная ошибка сервера")));
    }
}
