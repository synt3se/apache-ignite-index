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
public class GetCommandProcessor extends BotCommandProcessor {
    private final ImageSearchService imageSearchService;

    public GetCommandProcessor(ImageSearchService imageSearchService, BotMessageService messageService) {
        super(messageService);
        this.imageSearchService = imageSearchService;
    }

    @Override
    protected String getCommandName() {
        return "/id";
    }
    @Override
    protected String getReplyPrompt() {
        return "Отправьте id нужной записи в ответ на это сообщение";
    }

    @Override
    public boolean canProcessCondition(Update update) {
        return update.getMessage().hasText();
    }

    @Override
    public void processArgument(Update update, long chatId, AbsSender sender) {
        Message message = update.getMessage();
        int userMessageId = message.getMessageId();
        String idValue = message.getText().trim();

        Long id = parseId(idValue, chatId, sender);
        if (id == null) return;

        Message statusMessage = messageService.sendText(sender, chatId, "Ищу запись по id...");
        if (statusMessage == null) return;
        int messageIdToEdit = statusMessage.getMessageId();

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