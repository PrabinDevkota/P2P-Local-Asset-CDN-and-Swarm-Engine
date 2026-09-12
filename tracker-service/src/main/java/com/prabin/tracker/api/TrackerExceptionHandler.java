package com.prabin.tracker.api;

import com.prabin.tracker.auth.PeerTokens;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Fail closed: bad announce/discovery payloads become 400, not a half-written peer. */
@RestControllerAdvice
public final class TrackerExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception ex) {
        String message = ex instanceof IllegalArgumentException
                ? ex.getMessage()
                : "malformed JSON";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
    }

    @ExceptionHandler(PeerTokens.InvalidTokenException.class)
    public ResponseEntity<Map<String, String>> unauthorized(PeerTokens.InvalidTokenException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", ex.getMessage()));
    }
}
