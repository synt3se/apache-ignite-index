package ru.nsu.fit.vector.telegram.command;

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
public class SearchImageCommandProcessor extends BotCommandProcessor {
    private final ImageSearchService imageSearchService;

    public SearchImageCommandProcessor(ImageSearchService imageSearchService, BotMessageService messageService) {
        super(messageService);
        this.imageSearchService = imageSearchService;
    }

    @Override
    protected String getCommandName() {
        return "/search_img";
    }
    @Override
    protected String getReplyPrompt() {
        return "Отправьте картинку, документ или ссылку на изображение в ответ на это сообщение";
    }

    @Override
    public boolean canProcessCondition(Update update) {
        return update.getMessage().hasText() ||
                update.getMessage().hasPhoto() ||
                update.getMessage().hasDocument();
    }

    @Override
    public void processArgument(Update update, long chatId, AbsSender sender) {
        Message message = update.getMessage();
        if (message.hasPhoto() || message.hasDocument()) {
            String fileId = null;
            if (message.hasPhoto()) {
                PhotoSize photo = message.getPhoto().stream()
                        .max(java.util.Comparator.comparing(PhotoSize::getFileSize))
                        .orElse(null);
                fileId = photo.getFileId();
            }
            else {
                String mime = update.getMessage().getDocument().getMimeType();
                 if (mime == null || !mime.startsWith("image/")) {
                     messageService.sendText(sender, chatId, "❌ Документ должен быть изображением.");
                 }
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
            if (!isLink(url)) {
                messageService.sendText(sender, chatId, "❌ Пожалуйста, отправьте корректную ссылку (http:// или https://) или изображение в ответ на то сообщение.");
                return;
            }

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
