package ru.nsu.fit.vector.telegram.service;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.telegram.telegrambots.bots.*;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import reactor.core.publisher.Mono;
import ru.nsu.fit.vector.telegram.Dto;
import ru.nsu.fit.vector.telegram.client.GatewayClient;
import ru.nsu.fit.vector.telegram.Dto.Neighbor;

import java.io.IOException;
import java.io.InputStream;

@Service
public class ImageSearchService {
    private GatewayClient client;
    private TelegramFileService telegramFileService;

    public ImageSearchService(GatewayClient client, TelegramFileService telegramFileService) {
        this.client = client;
        this.telegramFileService = telegramFileService;
    }

    public Mono<Neighbor[]> searchUrl(String imageUrl) {
        return client.searchUrl(imageUrl)
                .onErrorMap(error -> {
                    String msg = error.getMessage();
                    if (msg != null && msg.contains("Не удаётся перейти по вашей ссылке")) {
                        return new RuntimeException(msg + " Попробуйте отправить изображение файлом.");
                    }
                    return error;
                });
    }

    public Mono<Neighbor[]> searchFile(String fileId, TelegramLongPollingBot bot) throws TelegramApiException, IOException {
        InputStream is = telegramFileService.getFilePath(fileId, bot);
        byte[] imageBytes = is.readAllBytes();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return "telegram_image.png";
            }
        };
        body.add("file", resource);
        body.add("count", "5");

        return client.searchFile(fileId, body);
    }

    public Mono<Dto.VectorResponse> getVectorById(long id) {
        return client.getVectorById(id);
    }

    public Mono<Dto.VectorResponse> addVector(String link) {
        return client.addVector(link);
    }
}
