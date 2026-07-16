package ru.nsu.fit.vector.telegram.processors.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.bots.AbsSender;
import ru.nsu.fit.vector.telegram.Dto;
import ru.nsu.fit.vector.telegram.service.BotMessageService;
import ru.nsu.fit.vector.telegram.service.ImageSearchService;

import java.util.Collections;

// Команда получения записи в базе по id
@Component
public class GetCommandProcessor extends BotCommandProcessor {
    private final ImageSearchService imageSearchService;

    public GetCommandProcessor(ImageSearchService imageSearchService, BotMessageService messageService) {
        super(messageService);
        this.imageSearchService = imageSearchService;
    }

    @Override
    protected String getCommandName() {
        return "/id";
    }
    @Override
    protected String getReplyPrompt() {
        return "Отправьте id нужной записи в ответ на это сообщение";
    }

    @Override
    public boolean canProcessCondition(Update update) {
        return update.getMessage().hasText();
    }

    @Override
    public void processArgument(Update update, long chatId, AbsSender sender) {
        Message message = update.getMessage();
        String idValue = message.getText().trim();

        Long id = parseId(idValue, chatId, sender);
        if (id == null) return;

        Message statusMessage = messageService.sendText(sender, chatId, "Ищу запись по id...");
        if (statusMessage == null) return;
        int messageIdToEdit = statusMessage.getMessageId();

        imageSearchService.getVectorById(id).subscribe(
                response -> {
                    String resultText = formatStringResult(response);
                    messageService.sendTextWithMarkup(sender, chatId, resultText, "HTML", makeMarkup(response.id()));
                },
                error -> {
                    log.warn("Error fetching vector by ID: " + error.getMessage());
                    String errorText = getErrorMessage(error);
                    messageService.editText(sender, chatId, messageIdToEdit, errorText);
                }
        );
    }

    @Override
    protected String getNotFoundMessage() {
        return "❌ Объект с таким ID не найден в базе данных.";
    }

    private String formatStringResult(Dto.VectorResponse response) {
        String sourceValue;
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(response.metadata());

            sourceValue = jsonNode.get("source").asText();
        } catch (Exception e) {
            sourceValue = "unknown";
        }
        return String.format(
                "🆔 <code>%s</code>\n" +
                        "<a href=\"%s\">🔗 Изображение</a>\n" +
                        "↩\uFE0F Источник: %s\n\n",
                response.id(), response.url(), sourceValue
        );
    }
    private InlineKeyboardMarkup makeMarkup(long id) {
        InlineKeyboardButton downloadBtn = new InlineKeyboardButton();
        downloadBtn.setText("📥 Скачать полный вектор (.txt)");
        downloadBtn.setCallbackData("vec_" + id);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(Collections.singletonList(Collections.singletonList(downloadBtn)));
        return markup;
    }
}