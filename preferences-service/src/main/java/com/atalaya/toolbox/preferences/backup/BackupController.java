package com.atalaya.toolbox.preferences.backup;

import com.atalaya.toolbox.preferences.service.PlayerThemeService;
import com.atalaya.toolbox.preferences.service.UserPreferencesService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
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
@EnableScheduling
@RequestMapping("/api/preferences/backups")
public class BackupController {
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
        // Match the theme -> users lock order used by the theme service.
        synchronized (themes) {
            synchronized (users) {
                return backups.createBackup();
            }
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String id) throws IOException {
        Path archive = backups.download(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
            .contentLength(Files.size(archive)).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(backups.downloadFileName(id)).build().toString())
            .body(output -> {
                try {
                    Files.copy(archive, output);
                } finally {
                    backups.discardDownload(id);
                }
            });
    }

    @PostMapping(value = "/restore/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BackupService.RestorePreview preview(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("Choose a backup ZIP first.");
        Path upload = Files.createTempFile("atalaya-upload-", ".zip");
        try {
            file.transferTo(upload);
            return backups.prepareRestore(upload);
        } finally {
            Files.deleteIfExists(upload);
        }
    }

    @PostMapping("/restore/{id}")
    public BackupService.RestoreResult restore(@PathVariable String id) throws IOException {
        synchronized (themes) {
            synchronized (users) {
                return backups.restore(id, () -> {
                    users.loadProfiles();
                    themes.loadSavedThemes();
                });
            }
        }
    }

    @DeleteMapping("/restore/{id}")
    public void discardRestore(@PathVariable String id) throws IOException {
        backups.discardRestore(id);
    }
}
