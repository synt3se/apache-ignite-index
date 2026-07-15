package ru.nsu.fit.vector.telegram.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ForceReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

// Отвечает за отправку сообщений пользователю телеграм-бота
@Service
public class BotMessageService {
    private static final Logger log = LoggerFactory.getLogger(BotMessageService.class);

    public Message sendText(AbsSender sender, Long chatId, String text) {
        return sendText(sender, chatId, text, null);
    }
    public Message sendText(AbsSender sender, Long chatId, String text, String parseMode) {
        SendMessage sendMessage = new SendMessage(chatId.toString(), text);
        sendMessage.setParseMode(parseMode);
        try {
            return sender.execute(sendMessage);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chat: {}", chatId, e);
            return null;
        }
    }

    public void editText(AbsSender sender, Long chatId, Integer messageId, String newText) {
        editText(sender, chatId, messageId, newText, null);
    }
    public void editText(AbsSender sender, Long chatId, Integer messageId, String newText, String parseMode) {
        EditMessageText editMessage = new EditMessageText();
        editMessage.setChatId(chatId.toString());
        editMessage.setMessageId(messageId);
        editMessage.setText(newText);
        editMessage.setParseMode(parseMode);
        try {
            sender.execute(editMessage);
        } catch (TelegramApiException e) {
            log.error("Failed to edit message {} in chat {}", messageId, chatId, e);
        }
    }

    public Message sendForceReply(AbsSender sender, Long chatId, String text) {
        return sendForceReply(sender, chatId, text, null);
    }
    public Message sendForceReply(AbsSender sender, Long chatId, String text, String parseMode) {
        SendMessage sendMessage = new SendMessage(chatId.toString(), text);
        boolean hide_keyboard_after_reply = true;
        sendMessage.setReplyMarkup(new ForceReplyKeyboard(hide_keyboard_after_reply));
        sendMessage.setParseMode(parseMode);
        try {
            return sender.execute(sendMessage);
        } catch (TelegramApiException e) {
            log.error("Failed to send ForceReply message to chat {}", chatId, e);
            return null;
        }
    }

    public Message sendTextWithMarkup(AbsSender sender, Long chatId, String text, String parseMode, InlineKeyboardMarkup markup) {
        SendMessage sendMessage = new SendMessage(chatId.toString(), text);
        sendMessage.setParseMode(parseMode);
        sendMessage.setReplyMarkup(markup);
        try {
            return sender.execute(sendMessage);
        } catch (TelegramApiException e) {
            log.error("Failed to send message with markup to chat {}", chatId, e);
            return null;
        }
    }
    public void editMarkup(AbsSender sender, Long chatId, Integer messageId, InlineKeyboardMarkup newMarkup) {
        EditMessageReplyMarkup editMarkup = new EditMessageReplyMarkup();
        editMarkup.setChatId(chatId.toString());
        editMarkup.setMessageId(messageId);
        editMarkup.setReplyMarkup(newMarkup);
        try {
            sender.execute(editMarkup);
        } catch (TelegramApiException e) {
            log.error("Failed to edit markup for message {} in chat {}", messageId, chatId, e);
        }
    }
    public void editTextAndMarkup(AbsSender sender, Long chatId, Integer messageId, String newText, String parseMode, InlineKeyboardMarkup newMarkup) {
        EditMessageText editMessage = new EditMessageText();
        editMessage.setChatId(chatId.toString());
        editMessage.setMessageId(messageId);
        editMessage.setText(newText);
        editMessage.setParseMode(parseMode);
        editMessage.setReplyMarkup(newMarkup);
        try {
            sender.execute(editMessage);
        } catch (TelegramApiException e) {
            log.error("Failed to edit text and markup for message {} in chat {}", messageId, chatId, e);
        }
    }
}