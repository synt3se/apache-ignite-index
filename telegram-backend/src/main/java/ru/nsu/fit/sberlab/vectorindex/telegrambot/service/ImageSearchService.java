package ru.nsu.fit.sberlab.vectorindex.telegrambot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import reactor.core.publisher.Mono;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.Dto;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.client.GatewayClient;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.Dto.Neighbor;

import java.io.IOException;
import java.io.InputStream;

// Прослойка бизнес-логики между ботом и gateway-клиентом
@Service
public class ImageSearchService {
    protected static final Logger log = LoggerFactory.getLogger(ImageSearchService.class);

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

    public Mono<Dto.VectorResponse> addVector(String link, String user) {
        String metadata = "{\"source\": \"telegram user " + user + "\"}";
        return client.addVector(link, metadata);
    }

    public Mono<Dto.VectorResponse> deleteVector(long id) {
        return client.deleteVector(id);
    }
}