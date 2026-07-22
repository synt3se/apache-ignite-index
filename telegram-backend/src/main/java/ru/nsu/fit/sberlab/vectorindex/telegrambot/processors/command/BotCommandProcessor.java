package ru.nsu.fit.sberlab.vectorindex.telegrambot.processors.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.Dto;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.exception.GatewayException;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.exception.ImageDownloadException;
import ru.nsu.fit.sberlab.vectorindex.telegrambot.service.BotMessageService;

import java.util.Locale;

// Класс команды. От него наследуются все команды бота.
public abstract class BotCommandProcessor {
    protected static final Logger log = LoggerFactory.getLogger(BotCommandProcessor.class);
    protected final BotMessageService messageService;

    public BotCommandProcessor(BotMessageService messageService) {
        this.messageService = messageService;
    }

    protected abstract String getCommandName();
    protected abstract String getReplyPrompt();

    public abstract boolean canProcessCondition(Update update);
    public boolean canProcess(Update update) {
        if (!update.hasMessage()) return false;
        Message message = update.getMessage();
        Message prompt = message.getReplyToMessage();
        if (message.hasText() && message.getText().equals(getCommandName())) {
            return true;
        }
        if (prompt != null && prompt.hasText() && canProcessCondition(update)) {
            return prompt.getText().startsWith(getCommandName());
        }
        return false;
    }

    public void process(Update update, long chatId, AbsSender sender) {
        Message message = update.getMessage();
        if (this instanceof UnknownCommandProcessor ||
                message.getReplyToMessage() == null && message.getText() != null && message.getText().startsWith("/")) { // Ответ на саму команду
            log.info("Received command '{}' from chatId: {}", getCommandName(), chatId);
            if (hasArgument()) {
                messageService.sendForceReply(sender, chatId, getCommandName() + "\n" + getReplyPrompt());
            }
            else {
                messageService.sendText(sender, chatId, getReplyPrompt());
            }
        }
        else { // Ответ на аргументы команды
            processArgument(update, chatId, sender);
        }
    }
    public boolean hasArgument() {
        return true;
    }
    public abstract void processArgument(Update update, long chatId, AbsSender sender);

    protected Long parseId(String raw, long chatId, AbsSender sender) {
        try {
            Long id = Long.parseLong(raw);
            if (id < 1) {
                log.warn("Incorrect ID format: id <= 0");
                messageService.sendText(sender, chatId, "❌ Неверный формат id: '" + raw + "'. Число должно быть > 0.");
                return null;
            }
            return id;
        }
        catch (NumberFormatException e) {
            log.warn("Incorrect ID format: " + e.getMessage());
            messageService.sendText(sender, chatId, "❌ Неверный формат id: '" + raw + "'. id должен быть числом.");
            return null;
        }
    }

    protected boolean isLink(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }

    protected String getErrorMessage(Throwable error) {
        // gateway выключен
        if (error instanceof org.springframework.web.reactive.function.client.WebClientRequestException
                || error.getCause() instanceof java.net.ConnectException) {
            return "❌ Ошибка: Не удалось связаться с сервером. Сервис временно недоступен.";
        }

        // Таймаут
        if (error instanceof java.util.concurrent.TimeoutException) {
            return "❌ Время ожидания ответа от сервера истекло.";
        }

        if (error instanceof ImageDownloadException) {
            return "❌ Не удалось скачать изображение по указанной ссылке. Проверьте, что ссылка доступна публично.";
        }

        // Ошибки от шлюза (GatewayException)
        if (error instanceof GatewayException gatewayException) {
            int code = gatewayException.getStatusCode();

            switch (code) {
                case 404:
                    // Динамически определяем сообщение в зависимости от текущей команды!
                    return getNotFoundMessage();
                case 400:
                    return "❌ Некорректный запрос: " + gatewayException.getMessage();
                case 500:
                    return "❌ Внутренняя ошибка сервера. Пожалуйста, попробуйте позже.";
                default:
                    return "❌ Ошибка сервера (код " + code + "): " + gatewayException.getMessage();
            }
        }

        // Непредвиденные ошибки
        return "❌ Произошла непредвиденная ошибка: " + error.getMessage();
    }

    // Каждая команда переопределит этот метод, чтобы возвращать свой текст для 404 ошибки.
    protected String getNotFoundMessage() {
        return "❌ Запрашиваемый ресурс не найден.";
    }

    protected String getStringTop(Dto.Neighbor[] top) {
        StringBuilder string = new StringBuilder();
        string.append("✅ <b>РЕЗУЛЬТАТЫ ПОИСКА</b>\n\n");
        for (Dto.Neighbor neighbor : top) {
            String similarity = String.format(Locale.US, "%.1f", 100*(1 - neighbor.score()));

            string.append(String.format(
                    "🆔 <code>%s</code>\n" +
                            "\uD83D\uDD0D Сходство: <b>%s %%</b>\n" +
                            "<a href=\"%s\">🔗 Изображение</a>\n\n",
                    neighbor.id(), similarity, neighbor.url()
            ));
        }
        string.append("⬇\uFE0F <b>Предпросмотр самого похожего</b>\n");
        if (string.length() == 0) return "Сервер вернул пустой список :(";
        return string.toString();
    }

    protected String getUserNameOrDefault(AbsSender sender, long chatId, String defaultValue) {
        try {
            GetChat getChat = new GetChat(String.valueOf(chatId));
            Chat chat = sender.execute(getChat);

            return "@" + chat.getUserName();
        } catch (Exception e) {
            log.warn("Could not get username for chatId {}: {}", chatId, e.getMessage());
            return defaultValue;
        }
    }
}