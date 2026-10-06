package com.atalaya.toolbox.preferences.service;

import com.atalaya.toolbox.preferences.domain.PlayerThemeRequest;
import com.atalaya.toolbox.preferences.domain.UserProfileSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserPreferencesServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void migratesLegacyProfilesAndListenHistoryWithoutChangingLegacyFiles() throws Exception {
        Path mainLegacyFile = tempDirectory.resolve("music-player-users.json");
        Path oldLegacyFile = tempDirectory.resolve("music-player-users-old.json");
        String profileJson = """
            [
              {"username":"dani","selectedThemeId":"evergreen","listens":[
                {"path":"songs/first.mp3","name":"first.mp3","listenCount":3,"lastListenedAt":"2026-10-01T12:00:00Z"}
              ]}
            ]
            """;
        String oldProfileJson = """
            [
              {"username":"atalaya","selectedThemeId":"midnight","listens":[]},
              {"username":"dani","selectedThemeId":"midnight","listens":[
                {"path":"songs/first.mp3","name":"first.mp3","listenCount":1,"lastListenedAt":"2026-09-01T12:00:00Z"}
              ]}
            ]
            """;
        Files.writeString(mainLegacyFile, profileJson);
        Files.writeString(oldLegacyFile, oldProfileJson);
        ObjectMapper mapper = new ObjectMapper();
        UserPreferencesService service = new UserPreferencesService(
            mapper,
            tempDirectory.resolve("preferences").toString()
        );

        service.loadProfiles();

        assertEquals(List.of("atalaya", "dani"), service.listUsers().stream().map(UserProfileSummary::username).toList());
        assertEquals("evergreen", service.selectedTheme("dani", "midnight"));
        assertEquals(3, service.listens("dani").getFirst().listenCount());
        assertEquals(profileJson, Files.readString(mainLegacyFile));
        assertEquals(oldProfileJson, Files.readString(oldLegacyFile));
        assertTrue(Files.isRegularFile(tempDirectory.resolve("preferences/users.json")));
    }

    @Test
    void keepsProfileListenCountsSeparateAndPersistsThem() {
        ObjectMapper mapper = new ObjectMapper();
        String storage = tempDirectory.resolve("preferences").toString();
        UserPreferencesService service = new UserPreferencesService(mapper, storage);
        service.loadProfiles();
        service.selectOrCreate("Alice");
        service.selectOrCreate("bob");
        service.recordListen("alice", "songs/one.mp3");
        service.recordListen("alice", "songs/one.mp3");
        service.recordListen("bob", "songs/one.mp3");

        UserPreferencesService restored = new UserPreferencesService(mapper, storage);
        restored.loadProfiles();

        assertEquals(2, restored.listens("alice").getFirst().listenCount());
        assertEquals(1, restored.listens("bob").getFirst().listenCount());
    }

    @Test
    void rejectsInvalidUsernamesAndParentPathListenEntries() {
        UserPreferencesService service = new UserPreferencesService(
            new ObjectMapper(), tempDirectory.resolve("preferences").toString()
        );
        service.loadProfiles();
        assertThrows(IllegalArgumentException.class, () -> service.selectOrCreate("../alice"));
        service.selectOrCreate("alice");
        assertThrows(IllegalArgumentException.class, () -> service.recordListen("alice", "../outside.mp3"));
        assertThrows(IllegalArgumentException.class, () -> service.listens("unknown"));
    }

    @Test
    void themeCatalogAndPerUserSelectionAreOwnedByPreferencesService() {
        ObjectMapper mapper = new ObjectMapper();
        String storage = tempDirectory.resolve("preferences").toString();
        UserPreferencesService users = new UserPreferencesService(mapper, storage);
        users.loadProfiles();
        users.selectOrCreate("alice");
        users.selectOrCreate("bob");
        PlayerThemeService themes = new PlayerThemeService(mapper, users, storage);
        themes.loadSavedThemes();

        var selected = themes.select("alice", "evergreen");
        var created = themes.create("bob", theme("New palette", "#123456"));

        PlayerThemeService restored = new PlayerThemeService(mapper, users, storage);
        restored.loadSavedThemes();
        assertEquals("evergreen", selected.selectedThemeId());
        assertEquals("new-palette", created.selectedThemeId());
        assertEquals("evergreen", restored.current("alice").selectedThemeId());
        assertEquals("new-palette", restored.current("bob").selectedThemeId());
    }

    private static PlayerThemeRequest theme(String name, String background) {
        return new PlayerThemeRequest(
            name, "A custom theme", "dark", background, "#111d31", "#e9f0fb", "#98abc6",
            "#94c8ff", "#243550", "aurora", List.of("#4edfff", "#a274ff", "#ff67ce"),
            "bars", 48, 1
        );
    }
}
