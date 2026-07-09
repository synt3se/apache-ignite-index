package ru.nsu.fit.vector.telegram;

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

import java.util.Map;

@Component
public class ImageForwarderBot extends TelegramLongPollingBot {

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
                sendTextMessage(chatId, "Привет! Отправь мне URL картинки, и я перешлю её на сервер.");
                return;
            }

            // Простая проверка: строка должна начинаться как ссылка
            if (text.startsWith("http://") || text.startsWith("https://")) {
                processImageUrl(chatId, text);
            } else {
                sendTextMessage(chatId, "Пожалуйста, отправьте корректную ссылку, начинающуюся с http:// или https://");
            }
        }
    }

    private void processImageUrl(Long chatId, String imageUrl) {
        // 1. Отправляем пользователю промежуточное сообщение
        Message statusMessage = sendTextMessage(chatId, "Секунду, отправляю ссылку на сервер...");
        if (statusMessage == null) return;

        Integer messageIdToEdit = statusMessage.getMessageId();

        // 2. Формируем тело запроса для вашего сервера (в данном примере JSON: {"imageUrl": "..."})
        Map<String, String> requestBody = Map.of("imageUrl", imageUrl);

        // 3. Делаем асинхронный POST запрос
        webClient.post()
                .bodyValue(requestBody)
                .retrieve()
                // Предполагаем, что сервер возвращает ответ в формате JSON, мапим его в Map
                .bodyToMono(Map.class)
                .timeout(java.time.Duration.ofSeconds(30)) // таймаут 30 секунд
                .subscribe(
                        // Действие при успешном ответе от вашего сервера
                        serverResponse -> {
                            // Допустим, ваш сервер возвращает JSON вида {"message": "Текст ответа"}
                            String serverMessage = (String) serverResponse.getOrDefault("message", "Обработано успешно!");
                            editTextMessage(chatId, messageIdToEdit, "🤖 Ответ сервера:\n\n" + serverMessage);
                        },
                        // Действие в случае ошибки (сервер недоступен, 500 ошибка, таймаут и т.д.)
                        error -> {
                            editTextMessage(chatId, messageIdToEdit, "❌ Ошибка при обращении к серверу: " + error.getMessage());
                        }
                );
    }

    // Вспомогательный метод для отправки сообщений
    private Message sendTextMessage(Long chatId, String text) {
        SendMessage sendMessage = new SendMessage(chatId.toString(), text);
        try {
            return execute(sendMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
            return null;
        }
    }

    // Вспомогательный метод для редактирования сообщений
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