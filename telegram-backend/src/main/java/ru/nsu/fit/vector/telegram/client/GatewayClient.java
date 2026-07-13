package ru.nsu.fit.vector.telegram.client;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import ru.nsu.fit.vector.telegram.Dto;
import ru.nsu.fit.vector.telegram.Dto.Neighbor;
import ru.nsu.fit.vector.telegram.ImageDownloadException;

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
                .onStatus(HttpStatusCode::isError, clientResponse ->
                        clientResponse.bodyToMono(Map.class)
                                .flatMap(errorBody -> Mono.error(new RuntimeException(generateErrorMessage(errorBody.get("message"), errorBody.get("error")))))

                )
                .bodyToMono(Dto.VectorResponse.class)
                .timeout(java.time.Duration.ofSeconds(30));
    }

    private String generateErrorMessage(Object message, Object e) {
        String msg = message.toString();
        String error = e.toString();
        if (error.startsWith("image_download_error")) {
            msg = "Не удаётся перейти по вашей ссылке. Возможно, ссылка недействительна, или этот сайт блокирует наше соединение в целях безопасности.";
        }
        return msg;
    }

    private Mono<? extends Throwable> mapError(ClientResponse clientResponse) {
        return clientResponse.bodyToMono(Map.class)
                .flatMap(errorBody -> {
                    String errorSign = errorBody.get("error") != null ? errorBody.get("error").toString() : "";
                    String serverMessage = errorBody.get("message") != null ? errorBody.get("message").toString() : "Unknown error";

                    if ("image_download_error".equals(errorSign)) {
                        return Mono.error(new ImageDownloadException(serverMessage, "", null));
                    }
                    return Mono.error(new RuntimeException(serverMessage));
                });
    }
}
