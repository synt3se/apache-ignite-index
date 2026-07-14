package ru.nsu.fit.vector.telegram.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
