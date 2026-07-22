package ru.nsu.fit.sberlab.vectorindex.telegrambot.service;

import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.bots.*;
import org.telegram.telegrambots.meta.bots.*;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.InputStream;

@Service
public class TelegramFileService {
    public InputStream getFilePath(String fileId, TelegramLongPollingBot bot) throws TelegramApiException {
        GetFile getFileMethod = new GetFile();
        getFileMethod.setFileId(fileId);
        File file = bot.execute(getFileMethod);
        String filePath = file.getFilePath();

        return bot.downloadFileAsStream(filePath);
    }
}
