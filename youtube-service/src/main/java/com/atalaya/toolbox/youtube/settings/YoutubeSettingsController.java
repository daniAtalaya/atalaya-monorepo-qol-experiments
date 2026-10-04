package com.atalaya.toolbox.youtube.settings;

import com.atalaya.toolbox.youtube.settings.domain.YoutubeSettings;
import com.atalaya.toolbox.youtube.settings.service.YoutubeSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/youtube/settings")
public class YoutubeSettingsController {
    private final YoutubeSettingsService settingsService;

    public YoutubeSettingsController(YoutubeSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public YoutubeSettings getSettings() {
        return settingsService.current();
    }

    @PutMapping
    public YoutubeSettings updateSettings(@Valid @RequestBody YoutubeSettings settings) {
        return settingsService.update(settings);
    }
}
