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
    protected String getCommandName() {
        return "unknown";
    }
    @Override
    protected String getReplyPrompt() {
        return "Пожалуйста, отправьте поддерживаемую команду или ответьте на необходимое сообщение";
    }

    @Override
    public boolean canProcessCondition(Update update) {
        return false;
    }

    @Override
    public boolean hasArgument() {
        return false;
    };

    @Override
    public void processArgument(Update update, long chatId, AbsSender sender) {}
}
