package com.github.synt3se.gateway.web.exceptions;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Centralised error handling for the gateway.
 *
 * Every handler logs the failure with request context and returns a compact JSON body
 * ({error, status, path, message}) instead of a raw stack trace. Errors coming from a
 * downstream service (CLIP, index, image download) keep their original status and body,
 * so the caller sees the real reason.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Error returned by a downstream service: CLIP, index or image download (4xx/5xx). */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String, Object>> handleDownstream(RestClientResponseException e,
                                                                HttpServletRequest req) {
        HttpStatusCode status = e.getStatusCode();
        String downstreamBody = e.getResponseBodyAsString();
        String message = (downstreamBody != null && !downstreamBody.isBlank())
                ? downstreamBody
                : "downstream service returned " + status;
        log.warn("Downstream call for {} {} returned {}: {}",
                req.getMethod(), req.getRequestURI(), status, truncate(message, 300));
        return body(status, "downstream_error", req, truncate(message, 500));
    }

    /** Malformed request body (invalid JSON) maps to 400 instead of 500. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException e,
                                                                    HttpServletRequest req) {
        log.warn("Malformed request body on {} {}: {}",
                req.getMethod(), req.getRequestURI(), truncate(e.getMessage(), 160));
        return body(HttpStatus.BAD_REQUEST, "bad_request", req, "malformed request body (JSON)");
    }

    /** Explicitly raised status, e.g. missing text in ClipClient. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException e,
                                                            HttpServletRequest req) {
        log.warn("Request error on {} {}: {} {}",
                req.getMethod(), req.getRequestURI(), e.getStatusCode(), e.getReason());
        return body(e.getStatusCode(), "request_error", req,
                e.getReason() != null ? e.getReason() : e.getMessage());
    }

    /** Anything else maps to 500; the stack trace goes to the log, not to the caller. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", req,
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> handleResourceAccessException(
            ResourceAccessException ex,
            HttpServletRequest request) {

        String originalMessage = ex.getMessage();
        String targetService = "";

        if (originalMessage != null) {
            if (originalMessage.contains("/embed")) {
                targetService = "(CLIP service) ";
            } else if (originalMessage.contains("/vectors")) {
                targetService = "(Vector server) ";
            } //else if (originalMessage.contains("/search/image")) {
                //targetService = "(Image owner) ";
            //}
        }

        log.error("External service {}is down on {}: {}", targetService, request.getRequestURI(), ex.getMessage());

        String clientMessage = String.format("%s is unavailable.", targetService);

        return body(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable", request, clientMessage);
    }

    @ExceptionHandler(ImageDownloadException.class)
    public ResponseEntity<Map<String, Object>> handleImageDownloadException(
            ImageDownloadException ex,
            HttpServletRequest request) {

        log.error("Image download failed on {} for URL [{}]. Reason: {}",
                request.getRequestURI(), ex.getUrl(), ex.getCause() != null ? ex.getCause().getMessage() : "Empty body");

        String clientMessage = "Failed to download the image from the provided URL. Host is unreachable or connection timed out.";
        return body(HttpStatus.BAD_REQUEST, "image_download_error", request, clientMessage);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e, HttpServletRequest req) {
        log.warn("Bad request argument on {} {}: {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return body(HttpStatus.BAD_REQUEST, "bad_request", req, e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatusCode status, String error,
                                                     HttpServletRequest req, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", error,
                "status", status.value(),
                "path", req.getRequestURI(),
                "message", message != null ? message : ""));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "... (" + s.length() + " chars total)";
    }
}
