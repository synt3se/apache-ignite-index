package ru.nsu.fit.vector.telegram.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import reactor.core.publisher.Mono;
import ru.nsu.fit.vector.telegram.Dto;
import ru.nsu.fit.vector.telegram.client.GatewayClient;
import ru.nsu.fit.vector.telegram.Dto.Neighbor;
import ru.nsu.fit.vector.telegram.processors.command.BotCommandProcessor;

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

    public Mono<Neighbor[]> searchUrl(String imageUrl, String filter) {
        return client.searchUrl(imageUrl, filter);
    }

    public Mono<Neighbor[]> searchTxt(String text, String filter) {
        return client.searchTxt(text, filter);
    }

    public Mono<Neighbor[]> searchFile(String fileId, TelegramLongPollingBot bot, String filter) throws TelegramApiException, IOException {
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
        if (filter != null && !filter.isBlank()) {
            body.add("filter", filter);
        }

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