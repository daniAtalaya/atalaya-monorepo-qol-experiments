package com.atalaya.toolbox.preferences;

import com.atalaya.toolbox.preferences.backup.BackupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.util.Map;

@RestControllerAdvice
public class PreferencesExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(PreferencesExceptionHandler.class);

    @ExceptionHandler(BackupService.BusyException.class)
    public ResponseEntity<Map<String, String>> handleBackupBusy(BackupService.BusyException exception) {
        log.warn("Panic backup request conflicted: {}", exception.getMessage());
        return ResponseEntity.status(409).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(BackupService.RecoveryException.class)
    public ResponseEntity<Map<String, String>> handleBackupRecovery(BackupService.RecoveryException exception) {
        return ResponseEntity.internalServerError().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleUploadLimit(MaxUploadSizeExceededException exception) {
        log.warn("Panic backup upload exceeded the configured multipart limit");
        return ResponseEntity.status(413).body(Map.of("error", "Backup is larger than the server upload limit. Increase spring.servlet.multipart.max-file-size and max-request-size."));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> handleBackupIo(IOException exception) {
        log.error("Backup filesystem or transfer operation failed", exception);
        return ResponseEntity.internalServerError().body(Map.of("error", "Backup operation could not finish. Check free disk space and pause other services before restoring."));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleInvalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }
}
