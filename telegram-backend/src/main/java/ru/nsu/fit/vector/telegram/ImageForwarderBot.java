package ru.nsu.fit.vector.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import reactor.core.publisher.Mono;
import ru.nsu.fit.vector.telegram.Dto.Neighbor;
import ru.nsu.fit.vector.telegram.command.BotCommandProcessor;
import ru.nsu.fit.vector.telegram.command.UnknownCommandProcessor;
import ru.nsu.fit.vector.telegram.service.BotMessageService;
import ru.nsu.fit.vector.telegram.service.ImageSearchService;

import java.util.Arrays;
import java.util.List;


@Component
public class ImageForwarderBot extends TelegramLongPollingBot {
    static final Logger log = LoggerFactory.getLogger(ImageForwarderBot.class);

    private final String botUsername;
    private final List<BotCommandProcessor> commandProcessors;
    private final UnknownCommandProcessor unknownCommandProcessor;

    public ImageForwarderBot(
            @Value("${bot.token}") String botToken,
            @Value("${bot.username}") String botUsername,
            List<BotCommandProcessor> commandProcessors,
            BotMessageService messageService) {
        super(botToken);
        this.botUsername = botUsername;
        this.commandProcessors = commandProcessors;
        this.unknownCommandProcessor = new UnknownCommandProcessor(messageService);
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        Message message = update.getMessage();
        String text = message.getText();
        long chatId = message.getChatId();

        for (BotCommandProcessor processor : commandProcessors) {
            if (processor.canProcess(update)) {
                processor.process(update, chatId, this);
                return;
            }
        }
        unknownCommandProcessor.process(update, chatId, this);
    }


    private Message sendTextMessage(Long chatId, String text) {
        SendMessage sendMessage = new SendMessage(chatId.toString(), text);
        try {
            return execute(sendMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
            return null;
        }
    }
}