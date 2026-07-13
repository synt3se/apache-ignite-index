package ru.nsu.fit.vector.telegram.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;
import ru.nsu.fit.vector.telegram.service.BotMessageService;

@Component
public class StartCommandProcessor extends BotCommandProcessor {
    public StartCommandProcessor(BotMessageService messageService) {
        super(messageService);
    }

    @Override
    public boolean canProcess(Update update) {
        return update.hasMessage() && update.getMessage().hasText() && update.getMessage().getText().equals("/start");
    }

    @Override
    public void process(Update update, long chatId, AbsSender sender) {
        log.info("Received command '/start' from chatId: {}", chatId);
        String mes = "Привет! Этот бот умеет искать похожие картинки в подготовленной базе данных. Просто скинь файл или URL, и я выдам топ самых похожих картинок.";
        messageService.sendText(sender, chatId, mes);
    }
}
