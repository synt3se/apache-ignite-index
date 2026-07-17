package ru.nsu.fit.vector.telegram.processors.command;

import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;
import ru.nsu.fit.vector.telegram.service.BotMessageService;

// Класс команды, который используется, когда команда не была распознана
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
        return "";
    }
    @Override
    protected String getCommandButtonName() {
        return null;
    }

    @Override
    public void process(Update update, long chatId, AbsSender sender) {
        messageService.sendText(sender, chatId, "❌ Сообщение не распознано. Пожалуйста, отправьте поддерживаемую команду или ответьте на необходимое сообщение");
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
