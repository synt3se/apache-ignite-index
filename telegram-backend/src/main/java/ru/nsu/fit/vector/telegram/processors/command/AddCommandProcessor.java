package ru.nsu.fit.vector.telegram.processors.command;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;
import ru.nsu.fit.vector.telegram.Dto;
import ru.nsu.fit.vector.telegram.service.BotMessageService;
import ru.nsu.fit.vector.telegram.service.ImageSearchService;

// Команда добавления в базу данных.
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
                    String resultText = formatStringResult(response);
                    messageService.editText(sender, chatId, messageIdToEdit, resultText, "HTML");
                },
                error -> {
                    log.warn("Error adding vector: " + error.getMessage());
                    String errorText = getErrorMessage(error);
                    messageService.editText(sender, chatId, messageIdToEdit, errorText);
                }
        );
    }

    private String formatStringResult(Dto.VectorResponse response) {
        return String.format(
                "✅ <b>Успешно добавлено!\n</b>" +
                "🆔 <code>%s</code>\n\n" +
                "Вы можете увидеть эту запись с помощью /id",
                response.id(), response.url()
        );
    }
}