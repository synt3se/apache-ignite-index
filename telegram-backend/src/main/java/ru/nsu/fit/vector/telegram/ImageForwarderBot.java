package ru.nsu.fit.vector.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import reactor.core.publisher.Mono;
import ru.nsu.fit.vector.telegram.dto.Neighbor;

import java.io.InputStream;
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
        Message message = update.getMessage();
        String text = message.getText();
        Long chatId = message.getChatId();
        // Проверяем, что пришло именно текстовое сообщение
        if (update.hasMessage() && update.getMessage().hasText()) {
            if (text.equals("/start")) {
                log.info("Received command '/start' from chatId: {}", chatId);
                String mes;
                mes = "Привет! Этот бот умеет искать похожие картинки в подготовленной базе данных. Просто скинь файл или URL, и я выдам топ самых похожих картинок.";
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
        else if (message.hasPhoto()) {
            log.info("Received photo from chatId: {}", chatId);
            PhotoSize photo = message.getPhoto().stream()
                    .max(java.util.Comparator.comparing(PhotoSize::getFileSize))
                    .orElse(null);

            if (photo != null) {
                processImageFile(chatId, photo.getFileId());
            }
        }
        else if (message.hasDocument()) {
            log.info("Received uncompressed document from chatId: {}", chatId);
            String mimeType = message.getDocument().getMimeType();

            // Проверяем, что отправленный файл — это картинка
            if (mimeType != null && mimeType.startsWith("image/")) {
                processImageFile(chatId, message.getDocument().getFileId());
            } else {
                sendTextMessage(chatId, "Этот файл не является изображением. Пожалуйста, отправьте картинку.");
            }
        }
        else {
            log.info("Received unknown request");
            sendTextMessage(chatId, "Пожалуйста, отправьте ссылку или само изображение");
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
                .onStatus(HttpStatusCode::isError, clientResponse ->
                        clientResponse.bodyToMono(Map.class)
                                .flatMap(errorBody -> Mono.error(new RuntimeException(generateErrorMessage(errorBody.get("message"), errorBody.get("error")))))
                )
                .bodyToMono(Neighbor[].class)
                .timeout(java.time.Duration.ofSeconds(30))
                .subscribe(
                        serverResponse -> {
                            log.info("Gateway answer: " + serverResponse);
                            editTextMessage(chatId, messageIdToEdit, getStringTop(serverResponse));
                        },
                        error -> {
                            log.warn("Gateway error answer: " + error.getMessage());
                            editTextMessage(chatId, messageIdToEdit, "❌ Ошибка: " + error.getMessage());
                        }
                );
    }

    private void processImageFile(Long chatId, String fileId) {
        Message statusMessage = sendTextMessage(chatId, "Скачиваю изображение из Telegram и анализирую...");
        if (statusMessage == null) return;
        Integer messageIdToEdit = statusMessage.getMessageId();

        try {
            // Запрашиваем путь к файлу у Telegram API
            GetFile getFileMethod = new GetFile();
            getFileMethod.setFileId(fileId);
            File file = execute(getFileMethod);
            String filePath = file.getFilePath();

            // Выкачиваем байты фотографии
            InputStream is = downloadFileAsStream(filePath);
            byte[] imageBytes = is.readAllBytes();

            // Собираем Multipart-форму для гейтвея
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

            // Передаём байты
            ByteArrayResource resource = new ByteArrayResource(imageBytes) {
                @Override
                public String getFilename() {
                    return "telegram_image.png";
                }
            };
            body.add("file", resource);
            body.add("count", "5");

            webClient.post()
                    .uri("/search/image/file")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(body))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, clientResponse ->
                            clientResponse.bodyToMono(Map.class)
                                    .flatMap(errorBody -> Mono.error(new RuntimeException(generateErrorMessage(errorBody.get("message"), errorBody.get("error")))))
                    )
                    .bodyToMono(Neighbor[].class)
                    .timeout(java.time.Duration.ofSeconds(30))
                    .subscribe(
                            serverResponse -> editTextMessage(chatId, messageIdToEdit, getStringTop(serverResponse)),
                            error -> {
                                log.warn("Gateway error file answer: " + error.getMessage());
                                editTextMessage(chatId, messageIdToEdit, "❌ Ошибка: " + error.getMessage());
                            }
                    );

        } catch (Exception e) {
            log.error("Failed to process photo from Telegram", e);
            editTextMessage(chatId, messageIdToEdit, "❌ Произошла ошибка при получении файла из Telegram.");
        }
    }

    private String generateErrorMessage(Object message, Object error) {
        String msg = message.toString();
        if ("image_download_error".equals(error)) {
            msg = "Не удаётся скачать картинку по вашей ссылке. Возможно, этот сайт блокирует наше соединение в целях безопасности. Попробуйте другой URL.";
        }
        return msg;
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