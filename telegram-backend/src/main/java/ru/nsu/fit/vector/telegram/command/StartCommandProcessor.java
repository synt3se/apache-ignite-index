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
public class StartCommandProcessor implements BotCommandProcessor {
    private static final Logger log = LoggerFactory.getLogger(StartCommandProcessor.class);
    private final ImageSearchService imageSearchService;
    private final BotMessageService messageService;

    public StartCommandProcessor(ImageSearchService imageSearchService, BotMessageService messageService) {
        this.imageSearchService = imageSearchService;
        this.messageService = messageService;
    }

    @Override
    public boolean canProcess(Update update) {
        return update.hasMessage() && update.getMessage().hasText() && update.getMessage().getText().equals("/start");
    }

    @Override
    public void process(Update update, long chatId, AbsSender sender) {
        String mes = "Привет! Этот бот умеет искать похожие картинки в подготовленной базе данных. Просто скинь файл или URL, и я выдам топ самых похожих картинок.";
        messageService.sendText(sender, chatId, mes);
    }
}
