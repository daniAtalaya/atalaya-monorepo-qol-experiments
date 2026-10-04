package com.atalaya.toolbox.youtube.themes;

import com.atalaya.toolbox.youtube.themes.domain.PlayerThemeRequest;
import com.atalaya.toolbox.youtube.themes.domain.PlayerThemeSelectionRequest;
import com.atalaya.toolbox.youtube.themes.service.PlayerThemeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/youtube/player-theme")
public class PlayerThemeController {
    private final PlayerThemeService themeService;

    public PlayerThemeController(PlayerThemeService themeService) {
        this.themeService = themeService;
    }

    @GetMapping
    public PlayerThemeService.ThemeCatalog getTheme(@RequestHeader("X-Atalaya-Username") String username) {
        return themeService.current(username);
    }

    @PutMapping
    public PlayerThemeService.ThemeCatalog updateTheme(
        @RequestHeader("X-Atalaya-Username") String username,
        @Valid @RequestBody PlayerThemeSelectionRequest request
    ) {
        return themeService.select(username, request.themeId());
    }

    @PutMapping("/selection")
    public PlayerThemeService.ThemeCatalog selectTheme(
        @RequestHeader("X-Atalaya-Username") String username,
        @Valid @RequestBody PlayerThemeSelectionRequest request
    ) {
        return themeService.select(username, request.themeId());
    }

    @PostMapping
    public PlayerThemeService.ThemeCatalog createTheme(
        @RequestHeader("X-Atalaya-Username") String username,
        @Valid @RequestBody PlayerThemeRequest request
    ) {
        return themeService.create(username, request);
    }

    @PutMapping("/{themeId}")
    public PlayerThemeService.ThemeCatalog updateTheme(
        @RequestHeader("X-Atalaya-Username") String username,
        @PathVariable String themeId,
        @Valid @RequestBody PlayerThemeRequest request
    ) {
        return themeService.update(username, themeId, request);
    }

    @DeleteMapping("/{themeId}")
    public PlayerThemeService.ThemeCatalog deleteTheme(
        @RequestHeader("X-Atalaya-Username") String username,
        @PathVariable String themeId
    ) {
        return themeService.delete(username, themeId);
    }
}
