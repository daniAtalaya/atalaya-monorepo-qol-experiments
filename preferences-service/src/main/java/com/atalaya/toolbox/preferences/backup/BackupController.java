package com.atalaya.toolbox.preferences.backup;

import com.atalaya.toolbox.preferences.service.PlayerThemeService;
import com.atalaya.toolbox.preferences.service.UserPreferencesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/preferences/backups")
public class BackupController {
    private static final Logger log = LoggerFactory.getLogger(BackupController.class);
    private final BackupService backups;
    private final UserPreferencesService users;
    private final PlayerThemeService themes;

    public BackupController(BackupService backups, UserPreferencesService users, PlayerThemeService themes) {
        this.backups = backups;
        this.users = users;
        this.themes = themes;
    }

    @PostMapping
    public BackupService.BackupDownload create() throws IOException {
        return backups.createBackup();
    }

    @GetMapping("/{id}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String id) {
        BackupService.DownloadDetails archive = backups.downloadDetails(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
            .contentLength(archive.sizeBytes()).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(archive.fileName()).build().toString())
            .body(output -> backups.streamDownload(id, output));
    }

    @PostMapping(value = "/restore/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BackupService.RestorePreview preview(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("Choose a backup ZIP first.");
        long started = System.nanoTime();
        log.info("Panic restore upload received: archiveBytes={}", file.getSize());
        Path upload = Files.createTempFile("atalaya-upload-", ".zip");
        try {
            file.transferTo(upload);
            BackupService.RestorePreview preview = backups.prepareRestore(upload);
            log.info("Panic restore upload {} prepared: durationMs={}", preview.id(), (System.nanoTime() - started) / 1_000_000);
            return preview;
        } finally {
            backups.discardUpload(upload);
        }
    }

    @PostMapping("/restore/{id}")
    public BackupService.RestoreResult restore(@PathVariable String id) throws IOException {
        return backups.restore(id, () -> {
            users.loadProfiles();
            themes.loadSavedThemes();
            log.info("Panic restore {} preferences and themes reloaded", id);
        }, users::withStorageLock);
    }

    @DeleteMapping("/restore/{id}")
    public void discardRestore(@PathVariable String id) throws IOException {
        backups.discardRestore(id);
    }
}
