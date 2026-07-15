package ru.nsu.fit.vector.telegram.processors.command;

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
    protected String getCommandName() {
        return "/start";
    }
    @Override
    protected String getReplyPrompt() {
        return "Привет! Этот бот умеет работать с векторной базой данных картинок. Например, искать похожие. Для этого просто введи команду /search_img, затем скинь файл или URL, и я выдам топ самых похожих картинок. Полный список команд и их описаний есть в меню";
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
