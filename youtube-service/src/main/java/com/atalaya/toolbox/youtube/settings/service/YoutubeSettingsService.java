package com.atalaya.toolbox.youtube.settings.service;

import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import com.atalaya.toolbox.youtube.settings.domain.YoutubeSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
public class YoutubeSettingsService {
    private final YoutubeProperties properties;
    private final ObjectMapper objectMapper;
    private final Path settingsFile;

    public YoutubeSettingsService(
        YoutubeProperties properties,
        ObjectMapper objectMapper,
        @Value("${toolbox.youtube.settings-file:../.data/music/youtube-settings.json}") String settingsFile
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.settingsFile = Path.of(System.getProperty("user.dir")).resolve(settingsFile).normalize();
    }

    @PostConstruct
    public void loadSavedSettings() {
        if (!Files.exists(settingsFile)) return;
        try {
            YoutubeSettings saved = objectMapper.readValue(settingsFile.toFile(), YoutubeSettings.class);
            if (".data".equals(saved.storageDirectory()) && properties.getStorageDirectory() != null) {
                saved = new YoutubeSettings(properties.getStorageDirectory().toString(), saved.jsRuntime(),
                    saved.ejsRemoteComponents(), saved.playlistDownloadAttempts(), saved.playlistRetryDelayMillis());
            }
            apply(saved);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not load saved YouTube settings from " + settingsFile, exception);
        }
    }

    public YoutubeSettings current() {
        return new YoutubeSettings(
            properties.getStorageDirectory() == null ? "" : properties.getStorageDirectory().toString(),
            properties.getJsRuntime(),
            properties.getEjsRemoteComponents(),
            properties.getPlaylistDownloadAttempts(),
            properties.getPlaylistRetryDelayMillis()
        );
    }

    public synchronized YoutubeSettings update(YoutubeSettings settings) {
        validate(settings);
        Path temporaryFile = settingsFile.resolveSibling(settingsFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(settingsFile.getParent());
            objectMapper.writeValue(temporaryFile.toFile(), settings);
            try {
                Files.move(temporaryFile, settingsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, settingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save YouTube settings to " + settingsFile, exception);
        }
        apply(settings);
        return current();
    }

    private void apply(YoutubeSettings settings) {
        validate(settings);
        properties.setStorageDirectory(settings.storageDirectory());
        properties.setJsRuntime(settings.jsRuntime());
        properties.setEjsRemoteComponents(settings.ejsRemoteComponents());
        properties.setPlaylistDownloadAttempts(settings.playlistDownloadAttempts());
        properties.setPlaylistRetryDelayMillis(settings.playlistRetryDelayMillis());
    }

    private void validate(YoutubeSettings settings) {
        if (settings.storageDirectory() == null || settings.storageDirectory().isBlank()) {
            throw new IllegalArgumentException("Storage directory must not be blank.");
        }
        if (settings.jsRuntime() == null || settings.jsRuntime().isBlank()) {
            throw new IllegalArgumentException("JavaScript runtime must not be blank.");
        }
        if (settings.ejsRemoteComponents() == null || settings.ejsRemoteComponents().isBlank()) {
            throw new IllegalArgumentException("EJS remote components must not be blank.");
        }
        if (settings.playlistDownloadAttempts() < 1) {
            throw new IllegalArgumentException("Playlist download attempts must be at least 1.");
        }
        if (settings.playlistRetryDelayMillis() < 0) {
            throw new IllegalArgumentException("Playlist retry delay must not be negative.");
        }
        try {
            Path.of(settings.storageDirectory());
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Storage directory is not a valid path.", exception);
        }
    }
}
