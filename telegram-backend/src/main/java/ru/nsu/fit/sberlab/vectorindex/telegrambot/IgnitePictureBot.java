package ru.nsu.fit.sberlab.vectorindex.telegrambot;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.processors.callback.VectorCallbackProcessor;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.processors.command.BotCommandProcessor;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.processors.command.UnknownCommandProcessor;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.service.BotMessageService;

import java.util.List;


@Component
public class IgnitePictureBot extends TelegramLongPollingBot {
    protected static final Logger log = LoggerFactory.getLogger(IgnitePictureBot.class);

    private final String botUsername;
    private final List<BotCommandProcessor> commandProcessors;
    private final UnknownCommandProcessor unknownCommandProcessor;
    private final VectorCallbackProcessor vectorCallbackProcessor;

    public IgnitePictureBot(
            @Value("${bot.token}") String botToken,
            @Value("${bot.username}") String botUsername,
            List<BotCommandProcessor> commandProcessors,
            BotMessageService messageService,
            VectorCallbackProcessor vectorCallbackProcessor) {
        super(botToken);
        this.botUsername = botUsername;
        this.commandProcessors = commandProcessors;
        this.unknownCommandProcessor = new UnknownCommandProcessor(messageService);
        this.vectorCallbackProcessor = vectorCallbackProcessor;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasCallbackQuery()) {
            if (vectorCallbackProcessor.canProcess(update.getCallbackQuery())) {
                vectorCallbackProcessor.process(update.getCallbackQuery(), this);
            }
            return;
        }

        if (update.hasMessage()) {
            Message message = update.getMessage();
            long chatId = message.getChatId();

            for (BotCommandProcessor processor : commandProcessors) {
                if (processor.canProcess(update)) {
                    processor.process(update, chatId, this);
                    return;
                }
            }
            unknownCommandProcessor.process(update, chatId, this);
        }
    }

    @PostConstruct
    public void clearPendingUpdates() {
        try {
            log.info("Clearing old Telegram messages...");

            DeleteWebhook deleteWebhook = new DeleteWebhook();
            deleteWebhook.setDropPendingUpdates(true); // Сбрасываем очередь накопившихся апдейтов

            this.execute(deleteWebhook);

            log.info("Old message queue cleared.");
        } catch (TelegramApiException e) {
            log.error("Could not clear old message queue: {}", e.getMessage());
        }
    }
}