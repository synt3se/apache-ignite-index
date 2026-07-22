package ru.nsu.fit.sberlab.vectorindex.telegrambot.processors.command;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.service.BotMessageService;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.service.ImageSearchService;

// Команда нахождения топ 5 записей по тексту.
@Component
public class SearchTextCommandProcessor extends BotCommandProcessor {
    private final ImageSearchService imageSearchService;

    public SearchTextCommandProcessor(ImageSearchService imageSearchService, BotMessageService messageService) {
        super(messageService);
        this.imageSearchService = imageSearchService;
    }

    @Override
    protected String getCommandName() {
        return "/search_txt";
    }
    @Override
    protected String getReplyPrompt() {
        return "Отправьте описание изображения текстом в ответ на это сообщение";
    }

    @Override
    public boolean canProcessCondition(Update update) {
        return update.getMessage().hasText();
    }

    @Override
    public void processArgument(Update update, long chatId, AbsSender sender) {
        String content = update.getMessage().getText().trim();
        Message statusMessage = messageService.sendText(sender, chatId, "Ищу картинки по тексту...");
        if (statusMessage == null) return;
        int messageIdToEdit = statusMessage.getMessageId();

        imageSearchService.searchTxt(content).subscribe(
                serverResponse -> messageService.editText(sender, chatId, messageIdToEdit, getStringTop(serverResponse), "HTML"),
                error -> {
                    log.warn("Failed to search by text: " + error.getMessage());
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
