package ru.nsu.fit.vector.telegram.processors.callback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.bots.AbsSender;
import ru.nsu.fit.vector.telegram.service.ImageSearchService;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Component
public class VectorCallbackProcessor {
    private static final Logger log = LoggerFactory.getLogger(VectorCallbackProcessor.class);
    private final ImageSearchService imageSearchService;

    public VectorCallbackProcessor(ImageSearchService imageSearchService) {
        this.imageSearchService = imageSearchService;
    }

    public boolean canProcess(CallbackQuery callbackQuery) {
        return callbackQuery.getData() != null && callbackQuery.getData().startsWith("vec_");
    }

    public void process(CallbackQuery callbackQuery, AbsSender sender) {
        String data = callbackQuery.getData();
        long chatId = callbackQuery.getMessage().getChatId();

        answerCallbackQuery(callbackQuery.getId(), sender);

        try {
            long vectorId = Long.parseLong(data.substring(4));

            imageSearchService.getVectorById(vectorId).subscribe(
                    response -> sendVectorFile(chatId, response.id(), response.vector(), sender),
                    error -> {
                        log.error("Failed to fetch vector for file download: {}", error.getMessage());
                        // Опционально: можно отправить сообщение об ошибке пользователю
                    }
            );
        } catch (NumberFormatException e) {
            log.error("Failed to parse vector ID from callback data: {}", data, e);
        }
    }

    private void sendVectorFile(long chatId, long vectorId, float[] vector, AbsSender sender) {
        if (vector == null) return;

        String vectorString = Arrays.toString(vector);
        byte[] fileBytes = vectorString.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream inputStream = new ByteArrayInputStream(fileBytes);

        InputFile inputFile = new InputFile(inputStream, "vector_" + vectorId + ".txt");

        SendDocument sendDocument = new SendDocument();
        sendDocument.setChatId(String.valueOf(chatId));
        sendDocument.setDocument(inputFile);

        try {
            sender.execute(sendDocument);
        } catch (Exception e) {
            log.error("Failed to send vector file to chat {}", chatId, e);
        }
    }

    private void answerCallbackQuery(String callbackQueryId, AbsSender sender) {
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackQueryId);
        try {
            sender.execute(answer);
        } catch (Exception e) {
            log.error("Failed to answer callback query {}", callbackQueryId, e);
        }
    }
}
