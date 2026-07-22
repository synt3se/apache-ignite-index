package ru.nsu.fit.sberlab.vectorindex.telegrambot.exception;

public class ImageDownloadException extends GatewayException {
    public ImageDownloadException(String message) {
        super(400, message);
    }
}