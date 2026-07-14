package ru.nsu.fit.vector.telegram.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ForceReplyKeyboard;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

// Отвечает за отправку сообщений пользователю телеграм-бота
@Service
public class BotMessageService {
    private static final Logger log = LoggerFactory.getLogger(BotMessageService.class);

    public Message sendText(AbsSender sender, Long chatId, String text) {
        SendMessage sendMessage = new SendMessage(chatId.toString(), text);
        try {
            return sender.execute(sendMessage);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chat: {}", chatId, e);
            return null;
        }
    }

    public void editText(AbsSender sender, Long chatId, Integer messageId, String newText) {
        EditMessageText editMessage = new EditMessageText();
        editMessage.setChatId(chatId.toString());
        editMessage.setMessageId(messageId);
        editMessage.setText(newText);
        try {
            sender.execute(editMessage);
        } catch (TelegramApiException e) {
            log.error("Failed to edit message {} in chat {}", messageId, chatId, e);
        }
    }

    public Message sendForceReply(AbsSender sender, Long chatId, String text) {
        SendMessage sendMessage = new SendMessage(chatId.toString(), text);
        boolean hide_keyboard_after_reply = true;
        sendMessage.setReplyMarkup(new ForceReplyKeyboard(hide_keyboard_after_reply));
        try {
            return sender.execute(sendMessage);
        } catch (TelegramApiException e) {
            log.error("Failed to send ForceReply message to chat {}", chatId, e);
            return null;
        }
    }
}