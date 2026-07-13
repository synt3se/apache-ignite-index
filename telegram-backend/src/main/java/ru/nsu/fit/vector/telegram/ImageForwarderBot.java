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
import ru.nsu.fit.vector.telegram.service.ImageSearchService;

import java.util.Arrays;
import java.util.List;


@Component
public class ImageForwarderBot extends TelegramLongPollingBot {
    static final Logger log = LoggerFactory.getLogger(ImageForwarderBot.class);

    private final String botUsername;
    private final List<BotCommandProcessor> commandProcessors;
    private ImageSearchService imageSearchService;

    public ImageForwarderBot(
            @Value("${bot.token}") String botToken,
            @Value("${bot.username}") String botUsername,
            List<BotCommandProcessor> commandProcessors) {
        super(botToken);
        this.botUsername = botUsername;
        this.commandProcessors = commandProcessors;
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
        log.info("Received unknown request pattern");
        sendTextMessage(chatId, "Пожалуйста, отправьте поддерживаемую команду, ссылку или изображение.");

        /*
        if (update.hasMessage() && update.getMessage().hasText()) {
            if (text.equals("/start")) {
                log.info("Received command '/start' from chatId: {}", chatId);
                String mes;
                mes = "Привет! Этот бот умеет искать похожие картинки в подготовленной базе данных. Просто скинь файл или URL, и я выдам топ самых похожих картинок.";
                sendTextMessage(chatId, mes);
                return;
            } else if (text.startsWith("/id")) {
                if (text.equals("/id")){
                    sendTextMessage(chatId, "Отправьте: '/id число'.");
                    return;
                }
                log.info("Received command '{}' from chatId: {}", text, chatId);
                String idValue = text.substring(4).trim();
                processGetById(chatId, idValue);
            } else if (text.startsWith("/add")) {
                if (text.equals("/add")){
                    sendTextMessage(chatId, "Отправьте: '/add ссылка'.");
                    return;
                }
                String link = text.substring(5).trim();
                if (!isLink(link)){
                    sendTextMessage(chatId, "Пожалуйста, отправьте корректную ссылку на изображение");
                    return;
                }
                log.info("Received command '{}' from chatId: {}", text, chatId);
                processAdd(chatId, link);
            } else if (isLink(text)) {
                log.info("Received url: " + text);
                processImageUrl(chatId, text);
            } else {
                log.info("Received unknown command: " + text);
                sendTextMessage(chatId, "Пожалуйста, отправьте ссылку или само изображение");
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

            // Проверяем, что отправленный файл - это картинка
            if (mimeType != null && mimeType.startsWith("image/")) {
                processImageFile(chatId, message.getDocument().getFileId());
            } else {
                sendTextMessage(chatId, "Этот файл не является изображением. Пожалуйста, отправьте картинку.");
            }
        }
        else {
            log.info("Received unknown request");
            sendTextMessage(chatId, "Пожалуйста, отправьте ссылку или само изображение");
        }*/
    }

    private void processImageUrl(Long chatId, String imageUrl) {
        Mono<Neighbor[]> searchMono = imageSearchService.searchUrl(imageUrl);
        handleSearchResponse(searchMono, chatId, "Отправляю ссылку на сервер...");
    }
    private void processImageFile(Long chatId, String fileId) {
        try {
            Mono<Neighbor[]> searchMono = imageSearchService.searchFile(fileId, this);
            handleSearchResponse(searchMono, chatId, "Скачиваю изображение из Telegram и анализирую...");
        } catch (Exception e) {
            log.error("Failed to process photo from Telegram", e);
            sendTextMessage(chatId, "❌ Произошла ошибка при получении файла из Telegram.");
        }
    }
    private void handleSearchResponse(Mono<Neighbor[]> searchMono, Long chatId, String statusText) {
        Message statusMessage = sendTextMessage(chatId, statusText);
        if (statusMessage == null) return;
        int messageIdToEdit = statusMessage.getMessageId();

        searchMono.subscribe(
                serverResponse -> editTextMessage(chatId, messageIdToEdit, getStringTop(serverResponse)),
                error -> {
                    log.warn("Gateway error answer: " + error.getMessage());
                    editTextMessage(chatId, messageIdToEdit, "❌ Ошибка: " + error.getMessage());
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

    private void processGetById(long chatId, String idValue) {
        Message statusMessage = sendTextMessage(chatId, "Ищу запись по id...");
        if (statusMessage == null) return;
        int messageIdToEdit = statusMessage.getMessageId();
        long id;
        try {
            id = Long.parseLong(idValue);
        }
        catch (NumberFormatException e) {
            log.warn("Incorrect ID format: " + e.getMessage());
            editTextMessage(chatId, messageIdToEdit, "❌ Неверный формат id: '" + idValue + "'. Отправьте: /id число.");
            return;
        }

        imageSearchService.getVectorById(id).subscribe(
                response -> {
                    String resultText =
                            "ID: " + response.id() + "\n" +
                            "URL: " + response.url();
                    if (response.metadata() != null && !response.metadata().isBlank()) {
                        resultText += "\nMETADATA: " + response.metadata();
                    }
                    editTextMessage(chatId, messageIdToEdit, resultText);
                },
                error -> {
                    log.warn("Error fetching vector by ID: " + error.getMessage());
                    editTextMessage(chatId, messageIdToEdit, "❌ Объект с ID " + id + " не найден в базе данных.");
                }
        );
    }

    private void processAdd(long chatId, String link) {
        Message statusMessage = sendTextMessage(chatId, "Добавляю запись в базу...");
        if (statusMessage == null) return;
        int messageIdToEdit = statusMessage.getMessageId();

        imageSearchService.addVector(link).subscribe(
                response -> {
                    String resultText =
                            "ID: " + response.id() + "\n" +
                                    "URL: " + response.url();
                    if (response.metadata() != null && !response.metadata().isBlank()) {
                        resultText += "\nMETADATA: " + response.metadata();
                    }
                    editTextMessage(chatId, messageIdToEdit, resultText);
                },
                error -> {
                    log.warn("Error fetching vector by ID: " + error.getMessage());
                    editTextMessage(chatId, messageIdToEdit, "❌ Не удалось добавить " + link + ". " + error.getMessage());
                }
        );
    }

    private boolean isLink(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
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