package ru.nsu.fit.vector.telegram.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.bots.AbsSender;
import ru.nsu.fit.vector.telegram.service.BotMessageService;

public abstract class BotCommandProcessor {
    protected static final Logger log = LoggerFactory.getLogger(BotCommandProcessor.class);;
    protected final BotMessageService messageService;

    public BotCommandProcessor(BotMessageService messageService) {
        this.messageService = messageService;
    }

    public abstract boolean canProcess(Update update);
    public abstract void process(Update update, long chatId, AbsSender sender);

    protected Long parseId(String raw, long chatId, AbsSender sender, int messageIdToEdit) {
        try {
            Long id = Long.parseLong(raw);
            if (id < 1) {
                log.warn("Incorrect ID format: id <= 0");
                messageService.editText(sender, chatId, messageIdToEdit, "❌ Неверный формат id: '" + raw + "'. Число должно быть > 0.");
                return null;
            }
            return id;
        }
        catch (NumberFormatException e) {
            log.warn("Incorrect ID format: " + e.getMessage());
            messageService.editText(sender, chatId, messageIdToEdit, "❌ Неверный формат id: '" + raw + "'. Отправьте: /id число.");
            return null;
        }
    }

    protected boolean isLink(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }
}