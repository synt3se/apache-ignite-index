package ru.nsu.fit.sberlab.vectorindex.telegrambot.processors.command;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.Dto;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.service.BotMessageService;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.service.ImageSearchService;

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
        return update.getMessage().hasText() ||
                update.getMessage().hasPhoto() ||
                update.getMessage().hasDocument();
    }

    @Override
    public void processArgument(Update update, long chatId, AbsSender sender) {
        Message message = update.getMessage();
        if (!update.getMessage().hasText()) {
            messageService.sendText(sender, chatId, "❌ В базу данных можно загружать только ссылки на картинки.");
        }

        String link = message.getText().trim();

        if (!isLink(link)) {
            messageService.sendText(sender, chatId, "❌ Пожалуйста, отправьте корректную ссылку на изображение (http:// или https://) в ответ на то сообщение.");
            return;
        }

        Message statusMessage = messageService.sendText(sender, chatId, "Добавляю запись в базу...");
        if (statusMessage == null) return;
        int messageIdToEdit = statusMessage.getMessageId();

        imageSearchService.addVector(link, getUserNameOrDefault(sender, chatId, String.valueOf(chatId))).subscribe(
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