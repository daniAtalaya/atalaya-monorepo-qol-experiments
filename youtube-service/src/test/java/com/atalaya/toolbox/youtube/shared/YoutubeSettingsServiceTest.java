package com.atalaya.toolbox.youtube.shared;

import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import com.atalaya.toolbox.youtube.settings.domain.YoutubeSettings;
import com.atalaya.toolbox.youtube.settings.service.YoutubeSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YoutubeSettingsServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndRestoresAllYoutubeSettings() {
        Path file = temporaryDirectory.resolve("settings.json");
        YoutubeProperties properties = new YoutubeProperties();
        YoutubeSettingsService service = new YoutubeSettingsService(properties, new ObjectMapper(), file.toString());
        YoutubeSettings settings = new YoutubeSettings(
            temporaryDirectory.resolve("media").toString(),
            "node",
            "ejs:npm",
            5,
            2500
        );

        assertEquals(settings, service.update(settings));

        YoutubeProperties restoredProperties = new YoutubeProperties();
        YoutubeSettingsService restoredService = new YoutubeSettingsService(
            restoredProperties,
            new ObjectMapper(),
            file.toString()
        );
        restoredService.loadSavedSettings();

        assertEquals(settings, restoredService.current());
    }
}
