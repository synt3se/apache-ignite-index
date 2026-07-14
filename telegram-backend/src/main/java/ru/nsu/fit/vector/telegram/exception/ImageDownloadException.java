package ru.nsu.fit.vector.telegram.exception;

public class ImageDownloadException extends GatewayException {
    public ImageDownloadException(String message) {
        // Ошибки скачивания изображений обычно соответствуют Bad Request (400)
        super(400, message);
    }
}