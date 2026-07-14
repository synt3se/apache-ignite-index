package ru.nsu.fit.vector.telegram.exception;

public class ImageDownloadException extends GatewayException {
    public ImageDownloadException(String message) {
        super(400, message);
    }
}