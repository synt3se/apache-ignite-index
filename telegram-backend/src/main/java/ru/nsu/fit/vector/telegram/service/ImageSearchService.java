package ru.nsu.fit.vector.telegram.service;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import reactor.core.publisher.Mono;
import ru.nsu.fit.vector.telegram.Dto;
import ru.nsu.fit.vector.telegram.client.GatewayClient;
import ru.nsu.fit.vector.telegram.Dto.Neighbor;

import java.io.IOException;
import java.io.InputStream;

@Service
public class ImageSearchService {
    private final GatewayClient client;
    private final TelegramFileService telegramFileService;

    public ImageSearchService(GatewayClient client, TelegramFileService telegramFileService) {
        this.client = client;
        this.telegramFileService = telegramFileService;
    }

    public Mono<Neighbor[]> searchUrl(String imageUrl) {
        return client.searchUrl(imageUrl);
    }

    public Mono<Neighbor[]> searchTxt(String text) {
        return client.searchTxt(text);
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

    public Mono<Dto.VectorResponse> deleteVector(long id) {
        return client.deleteVector(id);
    }
}