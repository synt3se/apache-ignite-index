package ru.nsu.fit.vector.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import reactor.core.publisher.Mono;
import ru.nsu.fit.vector.telegram.dto.Neighbor;

import java.util.List;
import java.util.Map;

@Component
public class ImageForwarderBot extends TelegramLongPollingBot {
    static final Logger log = LoggerFactory.getLogger(ImageForwarderBot.class);

    private final String botUsername;
    private final WebClient webClient;

    public ImageForwarderBot(
            @Value("${bot.token}") String botToken,
            @Value("${bot.username}") String botUsername,
            WebClient webClient) {
        super(botToken);
        this.botUsername = botUsername;
        this.webClient = webClient;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        // Проверяем, что пришло именно текстовое сообщение
        if (update.hasMessage() && update.getMessage().hasText()) {
            Message message = update.getMessage();
            String text = message.getText();
            Long chatId = message.getChatId();

            if (text.equals("/start")) {
                log.info("Received command /start");
                String mes;
                mes = "Привет! Этот бот умеет искать похожие картинки в подготовленной базе данных. Просто скинь URL, и я выдам топ самых похожих картинок.";
                sendTextMessage(chatId, mes);
                return;
            }

            if (text.startsWith("http://") || text.startsWith("https://")) {
                log.info("Received url: " + text);
                processImageUrl(chatId, text);
            } else {
                log.info("Received unknown command: " + text);
                sendTextMessage(chatId, "Пожалуйста, отправьте корректную ссылку, начинающуюся с http:// или https://");
            }
        }
    }

    private void processImageUrl(Long chatId, String imageUrl) {
        Message statusMessage = sendTextMessage(chatId, "Отправляю ссылку на сервер...");
        if (statusMessage == null) return;

        Integer messageIdToEdit = statusMessage.getMessageId();

        var requestBody = Map.of("url", imageUrl, "count", 5);
        webClient.post()
                .uri("/search/image")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Neighbor[].class)
                .timeout(java.time.Duration.ofSeconds(30))
                .subscribe(
                        serverResponse -> {
                            log.info("Gateway answer: " + serverResponse);
                            editTextMessage(chatId, messageIdToEdit, getStringTop(serverResponse));
                        },
                        error -> {
                            log.warn("Gateway error answer: " + error.getMessage());
                            editTextMessage(chatId, messageIdToEdit, "❌ Ошибка при обращении к серверу: " + error.getMessage());
                        }
                );
    }

    private String getStringTop(Neighbor[] top) {
        StringBuilder string = new StringBuilder();
        for (Neighbor neighbor : top) {
            string.append("id: " + neighbor.id() + "\n");
            string.append("distance: " + neighbor.score() + "\n");
            string.append(neighbor.url() + "\n\n");
        }
        if (string.length() == 0) return "Сервер вернул пустой список :(";
        return string.toString();
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

    private void editTextMessage(Long chatId, Integer messageId, String newText) {
        EditMessageText editMessage = new EditMessageText();
        editMessage.setChatId(chatId.toString());
        editMessage.setMessageId(messageId);
        editMessage.setText(newText);
        try {
            execute(editMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}