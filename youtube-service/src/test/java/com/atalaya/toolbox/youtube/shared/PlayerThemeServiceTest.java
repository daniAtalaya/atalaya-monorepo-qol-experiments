package com.atalaya.toolbox.youtube.shared;

import com.atalaya.toolbox.youtube.themes.service.PlayerThemeService;
import com.atalaya.toolbox.youtube.themes.domain.PlayerThemeRequest;
import com.atalaya.toolbox.youtube.users.service.MusicPlayerUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerThemeServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsSelectedThemeAndRestoresIt() {
        String file = temporaryDirectory.resolve("theme.json").toString();
        MusicPlayerUserService users = users(temporaryDirectory.resolve("users.json"));
        users.selectOrCreate("alice");
        users.selectOrCreate("bob");
        PlayerThemeService service = new PlayerThemeService(new ObjectMapper(), file, users);

        PlayerThemeService.ThemeCatalog selected = service.select("alice", "evergreen");
        PlayerThemeService restored = new PlayerThemeService(new ObjectMapper(), file, users);
        restored.loadSavedThemes();

        assertEquals("evergreen", selected.selectedThemeId());
        assertEquals("evergreen", restored.current("alice").selectedThemeId());
        assertEquals("midnight", restored.current("bob").selectedThemeId());
        assertEquals(4, restored.current("alice").themes().size());
    }

    @Test
    void rejectsUnknownTheme() {
        MusicPlayerUserService users = users(temporaryDirectory.resolve("users.json"));
        users.selectOrCreate("alice");
        PlayerThemeService service = new PlayerThemeService(new ObjectMapper(), temporaryDirectory.resolve("theme.json").toString(), users);

        assertThrows(IllegalArgumentException.class, () -> service.select("alice", "custom-css"));
    }

    @Test
    void createsUpdatesAndDeletesThemesInPersistentCatalog() {
        String file = temporaryDirectory.resolve("custom-themes.json").toString();
        MusicPlayerUserService users = users(temporaryDirectory.resolve("users.json"));
        users.selectOrCreate("alice");
        PlayerThemeService service = new PlayerThemeService(new ObjectMapper(), file, users);
        PlayerThemeService.ThemeCatalog created = service.create("alice", theme("My custom look", "#123456"));

        assertEquals("my-custom-look", created.selectedThemeId());
        assertEquals("#123456", created.themes().getLast().background());

        PlayerThemeService.ThemeCatalog updated = service.update("alice", "my-custom-look", theme("Renamed", "#234567"));
        assertEquals("#234567", updated.themes().getLast().background());

        PlayerThemeService.ThemeCatalog deleted = service.delete("alice", "my-custom-look");
        assertEquals(4, deleted.themes().size());
        assertEquals("midnight", deleted.selectedThemeId());
    }

    @Test
    void rejectsUnsafeThemeColorsAndVisualizerSettings() {
        MusicPlayerUserService users = users(temporaryDirectory.resolve("users.json"));
        users.selectOrCreate("alice");
        PlayerThemeService service = new PlayerThemeService(new ObjectMapper(), temporaryDirectory.resolve("invalid.json").toString(), users);

        assertThrows(IllegalArgumentException.class, () -> service.create("alice", theme("Broken", "url(javascript:alert(1))")));
        assertThrows(IllegalArgumentException.class, () -> service.create("alice", new PlayerThemeRequest(
            "Too loud", "Description", "dark", "#000000", "#111111", "#ffffff", "#aaaaaa", "#00ffff", "#333333",
            "aurora", java.util.List.of("#ffffff", "#ffffff", "#ffffff"), "wave", 120, 1
        )));
    }

    private MusicPlayerUserService users(Path file) {
        return new MusicPlayerUserService(new ObjectMapper(), file.toString());
    }

    private static PlayerThemeRequest theme(String name, String background) {
        return new PlayerThemeRequest(
            name, "Custom listening theme", "dark", background, "#111d31", "#e9f0fb", "#98abc6",
            "#94c8ff", "#243550", "aurora", java.util.List.of("#4edfff", "#a274ff", "#ff67ce"),
            "bars", 48, 1
        );
    }
}
