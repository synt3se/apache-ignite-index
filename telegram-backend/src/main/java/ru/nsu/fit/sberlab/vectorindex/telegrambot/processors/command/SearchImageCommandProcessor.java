package ru.nsu.fit.sberlab.vectorindex.telegrambot.processors.command;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;
import reactor.core.publisher.Mono;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.Dto;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.service.BotMessageService;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.service.ImageSearchService;

// Команда нахождения топ 5 похожих картинок.
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
        return "Отправьте картинку, файл или ссылку на изображение в ответ на это сообщение.\n\n" +
                "💡 Опционально: Добавьте фильтр по источнику во 2-й строки после ссылки. Для этого просто напишите название источника.";
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
            String fileId;
            if (message.hasPhoto()) {
                PhotoSize photo = message.getPhoto().stream()
                        .max(java.util.Comparator.comparing(PhotoSize::getFileSize))
                        .orElse(null);
                fileId = photo.getFileId();
            } else {
                String mime = message.getDocument().getMimeType();
                if (mime == null || !mime.startsWith("image/")) {
                    messageService.sendText(sender, chatId, "❌ Документ должен быть изображением.");
                    return;
                }
                fileId = message.getDocument().getFileId();
            }

            // Читаем фильтр из подписи к файлу/картинке (caption)
            String filter = message.getCaption() != null ? message.getCaption().trim() : "";

            try {
                Mono<Dto.Neighbor[]> searchMono = imageSearchService.searchFile(fileId, (TelegramLongPollingBot) sender, filter);
                handleSearchResponse(searchMono, chatId, "Скачиваю изображение из Telegram и анализирую...", sender);
            } catch (Exception e) {
                log.error("Failed to process photo from Telegram", e);
                messageService.sendText(sender, chatId, "❌ Произошла ошибка при получении файла из Telegram.");
            }
        } else {
            String fullText = message.getText().trim();
            String[] lines = fullText.split("\n", 2);
            String url = lines[0].trim();
            String filter = (lines.length > 1) ? lines[1].trim() : "";

            if (!isLink(url)) {
                messageService.sendText(sender, chatId, "❌ Пожалуйста, отправьте корректную ссылку (http:// или https://) или изображение в ответ на то сообщение.");
                return;
            }

            Mono<Dto.Neighbor[]> searchMono = imageSearchService.searchUrl(url, filter);
            handleSearchResponse(searchMono, chatId, "Отправляю на сервер...", sender);
        }
    }

    private void handleSearchResponse(Mono<Dto.Neighbor[]> searchMono, Long chatId, String statusText, AbsSender sender) {
        Message statusMessage = messageService.sendText(sender, chatId, statusText);
        if (statusMessage == null) return;
        int messageIdToEdit = statusMessage.getMessageId();

        searchMono.subscribe(
                serverResponse -> messageService.editText(sender, chatId, messageIdToEdit, getStringTop(serverResponse), "HTML"),
                error -> {
                    log.warn("Failed to search image: " + error.getMessage());
                    String errorText = getErrorMessage(error);
                    messageService.editText(sender, chatId, messageIdToEdit, errorText);
                }
        );
    }

    @Override
    protected String getNotFoundMessage() {
        return "❌ Похожих изображений не найдено.";
    }
}