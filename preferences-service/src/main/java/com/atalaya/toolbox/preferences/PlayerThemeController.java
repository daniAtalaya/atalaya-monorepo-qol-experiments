package com.atalaya.toolbox.preferences;

import com.atalaya.toolbox.preferences.domain.PlayerThemeRequest;
import com.atalaya.toolbox.preferences.domain.PlayerThemeSelectionRequest;
import com.atalaya.toolbox.preferences.service.PlayerThemeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/preferences/player-theme")
public class PlayerThemeController {
    private final PlayerThemeService themes;

    public PlayerThemeController(PlayerThemeService themes) {
        this.themes = themes;
    }

    @GetMapping
    public PlayerThemeService.ThemeCatalog current(@RequestHeader("X-Atalaya-Username") String username) {
        return themes.current(username);
    }

    @PutMapping
    public PlayerThemeService.ThemeCatalog updateSelection(
        @RequestHeader("X-Atalaya-Username") String username,
        @Valid @RequestBody PlayerThemeSelectionRequest request
    ) {
        return themes.select(username, request.themeId());
    }

    @PutMapping("/selection")
    public PlayerThemeService.ThemeCatalog select(
        @RequestHeader("X-Atalaya-Username") String username,
        @Valid @RequestBody PlayerThemeSelectionRequest request
    ) {
        return themes.select(username, request.themeId());
    }

    @PostMapping
    public PlayerThemeService.ThemeCatalog create(
        @RequestHeader("X-Atalaya-Username") String username,
        @Valid @RequestBody PlayerThemeRequest request
    ) {
        return themes.create(username, request);
    }

    @PutMapping("/{themeId}")
    public PlayerThemeService.ThemeCatalog update(
        @RequestHeader("X-Atalaya-Username") String username,
        @PathVariable String themeId,
        @Valid @RequestBody PlayerThemeRequest request
    ) {
        return themes.update(username, themeId, request);
    }

    @DeleteMapping("/{themeId}")
    public PlayerThemeService.ThemeCatalog delete(
        @RequestHeader("X-Atalaya-Username") String username,
        @PathVariable String themeId
    ) {
        return themes.delete(username, themeId);
    }
}
