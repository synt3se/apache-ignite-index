package ru.nsu.fit.vector.telegram.command;

import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;

public interface BotCommandProcessor {
    boolean canProcess(Update update);

    void process(Update update, long chatId, AbsSender sender);
}