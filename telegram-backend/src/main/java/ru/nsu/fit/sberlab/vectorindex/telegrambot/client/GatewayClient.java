package ru.nsu.fit.sberlab.vectorindex.telegrambot.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.Dto;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.Dto.Neighbor;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.exception.GatewayException;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.exception.ImageDownloadException;

import java.util.Map;

@Component
public class GatewayClient {
    private final WebClient webClient;

    public GatewayClient(WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<Neighbor[]> searchUrl(String imageUrl, String filter) {
        var requestBody = Map.of("url", imageUrl, "count", 5, "filter", filter);
        return webClient.post()
                .uri("/search/image")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::mapError)
                .bodyToMono(Neighbor[].class)
                .timeout(java.time.Duration.ofSeconds(30));
    }

    public Mono<Neighbor[]> searchTxt(String text, String filter) {
        var requestBody = Map.of("text", text, "count", 5, "filter", filter);
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

    public Mono<Dto.VectorResponse> addVector(String link, String metadata) {
        var requestBody = Map.of("url", link, "metadata", metadata);
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
        return clientResponse.bodyToMono(String.class)
                .flatMap(rawBody -> {
                    String errorSign = "";
                    String serverMessage = rawBody;
                    try {
                        ObjectMapper mapper = new ObjectMapper();
                        JsonNode jsonNode = mapper.readTree(rawBody);

                        if (jsonNode.has("error")) {
                            errorSign = jsonNode.get("error").asText();
                        }
                        if (jsonNode.has("message")) {
                            serverMessage = jsonNode.get("message").asText();
                        }
                    } catch (Exception ignored) {
                    }

                    if ("image_download_error".equals(errorSign)) {
                        return Mono.<Throwable>error(new ImageDownloadException(serverMessage));
                    }

                    return Mono.<Throwable>error(new GatewayException(statusCode, serverMessage));
                })
                .switchIfEmpty(Mono.<Throwable>error(
                        new GatewayException(statusCode, "Неизвестная ошибка сервера (пустое тело ответа)"))
                );
    }
}
