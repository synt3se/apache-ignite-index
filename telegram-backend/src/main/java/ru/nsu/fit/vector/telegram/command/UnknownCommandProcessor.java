package ru.nsu.fit.vector.telegram.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;
import ru.nsu.fit.vector.telegram.service.BotMessageService;

public class UnknownCommandProcessor extends BotCommandProcessor {
    public UnknownCommandProcessor(BotMessageService messageService) {
        super(messageService);
    }

    @Override
    public boolean canProcess(Update update) {
        return true;
    }

    @Override
    public void process(Update update, long chatId, AbsSender sender) {
        log.info("Received unknown request pattern from chatId: {}", chatId);
        String mes = "Пожалуйста, отправьте поддерживаемую команду, ссылку или изображение.";
        messageService.sendText(sender, chatId, mes);
    }
}
