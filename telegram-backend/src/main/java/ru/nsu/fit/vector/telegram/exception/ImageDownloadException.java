package ru.nsu.fit.vector.telegram.exception;

// Наследуемся от RuntimeException, чтобы не прокидывать throws в сигнатурах методов
public class ImageDownloadException extends RuntimeException {
    private final String url;

    public ImageDownloadException(String message, String url, Throwable cause) {
        super(message, cause);
        this.url = url;
    }

    public String getUrl() {
        return url;
    }
}
