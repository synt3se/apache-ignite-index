package ru.nsu.fit.sberlab.vectorindex.vectorserver.exception;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}