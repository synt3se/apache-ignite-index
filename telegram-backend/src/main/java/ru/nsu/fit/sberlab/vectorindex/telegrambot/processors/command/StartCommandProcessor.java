package ru.nsu.fit.sberlab.vectorindex.telegrambot.processors.command;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.service.BotMessageService;

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
        return "\uD83D\uDC4B Привет! Этот бот умеет работать с векторной базой данных картинок. \n\uD83D\uDD0D Например, искать похожие. Для этого просто введи команду \n/search_img, затем скинь файл или URL, и я выдам топ самых похожих картинок. \n⬇\uFE0F Полный список команд и их описаний есть в меню";
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
