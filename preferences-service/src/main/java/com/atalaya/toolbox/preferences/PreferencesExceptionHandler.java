package com.atalaya.toolbox.preferences;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.io.IOException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class PreferencesExceptionHandler {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleUploadLimit(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(413).body(Map.of("error", "Backup is larger than the server upload limit. Increase spring.servlet.multipart.max-file-size and max-request-size."));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> handleBackupIo(IOException exception) {
        return ResponseEntity.internalServerError().body(Map.of("error", "Backup operation could not finish. Check free disk space and pause other services before restoring."));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleInvalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }
}
