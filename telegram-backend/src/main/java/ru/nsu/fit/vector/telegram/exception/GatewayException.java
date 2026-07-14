package ru.nsu.fit.vector.telegram.exception;

public class GatewayException extends RuntimeException {
    private final int statusCode;

    public GatewayException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}