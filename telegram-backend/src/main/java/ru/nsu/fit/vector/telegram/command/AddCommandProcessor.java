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
    protected String getCommandName() {
        return "/add";
    }
    @Override
    protected String getReplyPrompt() {
        return "Отправьте ссылку на изображение в ответ на это сообщение";
    }

    @Override
    public boolean canProcessCondition(Update update) {
        return update.getMessage().hasText();
    }

    @Override
    public void processArgument(Update update, long chatId, AbsSender sender) {
        Message message = update.getMessage();
        String link = message.getText().trim();

        if (!isLink(link)) {
            messageService.sendText(sender, chatId, "❌ Пожалуйста, отправьте корректную ссылку на изображение (http:// или https://) в ответ на то сообщение.");
            return;
        }

        Message statusMessage = messageService.sendText(sender, chatId, "Добавляю запись в базу...");
        if (statusMessage == null) return;
        int messageIdToEdit = statusMessage.getMessageId();

        imageSearchService.addVector(link).subscribe(
                response -> {
                    String resultText = "✅ Успешно добавлено!\nID: " + response.id() + "\nURL: " + response.url();
                    if (response.metadata() != null && !response.metadata().isBlank()) {
                        resultText += "\nMETADATA: " + response.metadata();
                    }
                    messageService.editText(sender, chatId, messageIdToEdit, resultText);
                },
                error -> {
                    log.warn("Error adding vector: " + error.getMessage());
                    String errorText = getErrorMessage(error, "❌ Не удалось добавить запись.");
                    messageService.editText(sender, chatId, messageIdToEdit, errorText);                }
        );
    }
}