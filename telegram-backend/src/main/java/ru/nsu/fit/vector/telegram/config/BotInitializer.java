package ru.nsu.fit.vector.telegram.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.nsu.fit.vector.telegram.IgnitePictureBot;

@Configuration
public class BotInitializer {

    @Bean
    public TelegramBotsApi telegramBotsApi(IgnitePictureBot ignitePictureBot) throws TelegramApiException {
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(ignitePictureBot);
        return botsApi;
    }
}