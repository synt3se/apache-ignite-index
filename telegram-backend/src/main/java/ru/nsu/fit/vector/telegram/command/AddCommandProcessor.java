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
public class AddCommandProcessor extends BotCommandProcessor {
    private final ImageSearchService imageSearchService;

    public AddCommandProcessor(ImageSearchService imageSearchService, BotMessageService messageService) {
        super(messageService);
        this.imageSearchService = imageSearchService;
    }

    @Override
    public boolean canProcess(Update update) {
        return update.hasMessage() && update.getMessage().hasText() && update.getMessage().getText().startsWith("/add");
    }

    @Override
    public void process(Update update, long chatId, AbsSender sender) {
        String text = update.getMessage().getText().trim();
        log.info("Received command '{}' from chatId: {}", text, chatId);

        if (text.equals("/add")) {
            messageService.sendText(sender, chatId, "Отправьте: '/add ссылка'.");
            return;
        }

        String link = text.substring(5).trim();
        Message statusMessage = messageService.sendText(sender, chatId, "Добавляю запись в базу...");
        if (statusMessage == null) return;
        int messageIdToEdit = statusMessage.getMessageId();

        imageSearchService.addVector(link).subscribe(
                response -> {
                    String resultText = "ID: " + response.id() + "\nURL: " + response.url();
                    if (response.metadata() != null && !response.metadata().isBlank()) {
                        resultText += "\nMETADATA: " + response.metadata();
                    }
                    messageService.editText(sender, chatId, messageIdToEdit, resultText);
                },
                error -> {
                    log.warn("Error fetching vector by ID: " + error.getMessage());
                    messageService.editText(sender, chatId, messageIdToEdit, "❌ Не удалось добавить " + link + ". " + error.getMessage());
                }
        );
    }
}