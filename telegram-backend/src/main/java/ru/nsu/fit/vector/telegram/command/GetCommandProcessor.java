package ru.nsu.fit.vector.telegram.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;
import ru.nsu.fit.vector.telegram.service.BotMessageService;
import ru.nsu.fit.vector.telegram.service.ImageSearchService;

@Component
public class GetCommandProcessor implements BotCommandProcessor {
    private static final Logger log = LoggerFactory.getLogger(AddCommandProcessor.class);
    private final ImageSearchService imageSearchService;
    private final BotMessageService messageService;

    public GetCommandProcessor(ImageSearchService imageSearchService, BotMessageService messageService) {
        this.imageSearchService = imageSearchService;
        this.messageService = messageService;
    }

    @Override
    public boolean canProcess(Update update) {
        return update.hasMessage() && update.getMessage().hasText() && update.getMessage().getText().startsWith("/id");
    }

    @Override
    public void process(Update update, long chatId, AbsSender sender) {
        String text = update.getMessage().getText().trim();
        log.info("Received command '{}' from chatId: {}", text, chatId);

        if (text.equals("/id")) {
            messageService.sendText(sender, chatId, "Отправьте: '/id число'.");
            return;
        }

        String idValue = text.substring(4).trim();
        Message statusMessage = messageService.sendText(sender, chatId, "Ищу запись по id...");
        if (statusMessage == null) return;
        int messageIdToEdit = statusMessage.getMessageId();

        long id;
        try {
            id = Long.parseLong(idValue);
        }
        catch (NumberFormatException e) {
            log.warn("Incorrect ID format: " + e.getMessage());
            messageService.editText(sender, chatId, messageIdToEdit, "❌ Неверный формат id: '" + idValue + "'. Отправьте: /id число.");
            return;
        }

        imageSearchService.getVectorById(id).subscribe(
                response -> {
                    String resultText =
                            "ID: " + response.id() + "\n" +
                                    "URL: " + response.url();
                    if (response.metadata() != null && !response.metadata().isBlank()) {
                        resultText += "\nMETADATA: " + response.metadata();
                    }
                    messageService.editText(sender, chatId, messageIdToEdit, resultText);
                },
                error -> {
                    log.warn("Error fetching vector by ID: " + error.getMessage());
                    messageService.editText(sender, chatId, messageIdToEdit, "❌ Объект с ID " + id + " не найден в базе данных.");
                }
        );
    }
}