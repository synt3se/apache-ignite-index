package ru.nsu.fit.vector.telegram.processors.command;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;
import ru.nsu.fit.vector.telegram.service.BotMessageService;
import ru.nsu.fit.vector.telegram.service.ImageSearchService;

// Команда нахождения топ 5 записей по тексту.
@Component
public class SearchTextCommandProcessor extends BotCommandProcessor {
    private final ImageSearchService imageSearchService;
    public static final String BUTTON_NAME = "\uD83D\uDD0D Поиск по тексту";


    public SearchTextCommandProcessor(ImageSearchService imageSearchService, BotMessageService messageService) {
        super(messageService);
        this.imageSearchService = imageSearchService;
    }

    @Override
    protected String getCommandName() {
        return "/search_txt";
    }
    @Override
    protected String getCommandButtonName() {
        return BUTTON_NAME;
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
                serverResponse -> {
                    messageService.editText(sender, chatId, messageIdToEdit, getStringTop(serverResponse), "HTML");
                    messageService.sendWithMenu(sender, chatId, "...");
                },
                error -> {
                    log.warn("Failed to search by text: " + error.getMessage());
                    String errorText = getErrorMessage(error);
                    messageService.editText(sender, chatId, messageIdToEdit, errorText);
                    messageService.sendWithMenu(sender, chatId, "...");
                }
        );
    }

    @Override
    protected String getNotFoundMessage() {
        return "❌ Похожих изображений не найдено.";
    }
}
