package ru.nsu.fit.vector.telegram.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;
import reactor.core.publisher.Mono;
import ru.nsu.fit.vector.telegram.Dto;
import ru.nsu.fit.vector.telegram.service.BotMessageService;
import ru.nsu.fit.vector.telegram.service.ImageSearchService;

@Component
public class SearchCommandProcessor extends BotCommandProcessor {
    private final ImageSearchService imageSearchService;

    public SearchCommandProcessor(ImageSearchService imageSearchService, BotMessageService messageService) {
        super(messageService);
        this.imageSearchService = imageSearchService;
    }

    @Override
    public boolean canProcess(Update update) {
        return update.hasMessage() && update.getMessage().hasText() && isLink(update.getMessage().getText()) ||
                update.getMessage().hasPhoto() ||
                update.getMessage().hasDocument() && update.getMessage().getDocument().getMimeType() != null &&
                        update.getMessage().getDocument().getMimeType().startsWith("image/");
    }

    @Override
    public void process(Update update, long chatId, AbsSender sender) {
        Message message = update.getMessage();
        if (message.hasPhoto() || message.hasDocument()) {
            String fileId = null;
            if (message.hasPhoto()) {
                log.info("Received photo from chatId: {}", chatId);
                PhotoSize photo = message.getPhoto().stream()
                        .max(java.util.Comparator.comparing(PhotoSize::getFileSize))
                        .orElse(null);
                fileId = photo.getFileId();
            }
            else {
                fileId = message.getDocument().getFileId();
            }

            try {
                Mono<Dto.Neighbor[]> searchMono = imageSearchService.searchFile(fileId, (TelegramLongPollingBot)sender);
                handleSearchResponse(searchMono, chatId, "Скачиваю изображение из Telegram и анализирую...", sender);
            } catch (Exception e) {
                log.error("Failed to process photo from Telegram", e);
                messageService.sendText(sender, chatId, "❌ Произошла ошибка при получении файла из Telegram.");
            }
        }
        else {
            String url = update.getMessage().getText().trim();

            Mono<Dto.Neighbor[]> searchMono = imageSearchService.searchUrl(url);
            handleSearchResponse(searchMono, chatId, "Отправляю на сервер...", sender);
        }
    }

    private void handleSearchResponse(Mono<Dto.Neighbor[]> searchMono, Long chatId, String statusText, AbsSender sender) {
        Message statusMessage = messageService.sendText(sender, chatId, statusText);

        if (statusMessage == null) return;
        int messageIdToEdit = statusMessage.getMessageId();

        searchMono.subscribe(
                serverResponse -> messageService.editText(sender, chatId, messageIdToEdit, getStringTop(serverResponse)),
                error -> {
                    log.warn("Gateway error answer: " + error.getMessage());
                    messageService.editText(sender, chatId, messageIdToEdit, "❌ Ошибка: " + error.getMessage());
                }
        );
    }

    private String getStringTop(Dto.Neighbor[] top) {
        StringBuilder string = new StringBuilder();
        for (Dto.Neighbor neighbor : top) {
            string.append("id: " + neighbor.id() + "\n");
            string.append("distance: " + neighbor.score() + "\n");
            string.append(neighbor.url() + "\n\n");
        }
        if (string.length() == 0) return "Сервер вернул пустой список :(";
        return string.toString();
    }
}
