package com.atalaya.toolbox.youtube.themes.service;

import com.atalaya.toolbox.youtube.themes.domain.PlayerThemeOption;
import com.atalaya.toolbox.youtube.themes.domain.PlayerThemePreference;
import com.atalaya.toolbox.youtube.themes.domain.PlayerThemeRequest;
import com.atalaya.toolbox.youtube.users.service.MusicPlayerUserService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.regex.Pattern;

@Service
public class PlayerThemeService {
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Set<String> MODES = Set.of("dark", "light");
    private static final Set<String> BACKDROPS = Set.of("aurora", "halo", "plain");
    private static final Set<String> VISUALIZER_STYLES = Set.of("bars", "mirror", "wave");

    private final ObjectMapper objectMapper;
    private final Path themeFile;
    private final MusicPlayerUserService userService;
    private List<PlayerThemeOption> themes = defaultThemes();
    private String selectedThemeId = "midnight";

    public PlayerThemeService(
        ObjectMapper objectMapper,
        @Value("${toolbox.music-player.theme-file:.data/music-player-theme.json}") String themeFile,
        MusicPlayerUserService userService
    ) {
        this.objectMapper = objectMapper;
        this.userService = userService;
        try {
            this.themeFile = Path.of(System.getProperty("user.dir")).resolve(themeFile).normalize();
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Theme file path is invalid.", exception);
        }
    }

    @PostConstruct
    public synchronized void loadSavedThemes() {
        if (!Files.exists(themeFile)) return;
        try {
            PlayerThemePreference saved = objectMapper.readValue(themeFile.toFile(), PlayerThemePreference.class);
            if (saved.themes() != null && !saved.themes().isEmpty()) {
                List<PlayerThemeOption> loadedThemes = saved.themes().stream().map(this::validate).toList();
                if (loadedThemes.stream().map(PlayerThemeOption::id).distinct().count() != loadedThemes.size()) {
                    throw new IllegalArgumentException("Theme IDs must be unique.");
                }
                themes = loadedThemes;
            }
            selectedThemeId = themes.stream()
                .anyMatch(theme -> theme.id().equals(saved.selectedThemeId()))
                ? saved.selectedThemeId()
                : themes.getFirst().id();
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not load saved music player themes from " + themeFile, exception);
        }
    }

    public synchronized ThemeCatalog current(String username) {
        String selectedForUser = userService.selectedTheme(username, selectedThemeId);
        String resolvedThemeId = themes.stream().anyMatch(theme -> theme.id().equals(selectedForUser))
            ? selectedForUser
            : themes.getFirst().id();
        return new ThemeCatalog(resolvedThemeId, List.copyOf(themes));
    }

    public synchronized ThemeCatalog select(String username, String themeId) {
        requireTheme(themeId);
        userService.selectTheme(username, themeId);
        return current(username);
    }

    public synchronized ThemeCatalog create(String username, PlayerThemeRequest request) {
        PlayerThemeOption theme = toTheme(request, uniqueId(request.name()));
        List<PlayerThemeOption> updated = new ArrayList<>(themes);
        updated.add(theme);
        persist(updated, selectedThemeId);
        themes = List.copyOf(updated);
        userService.selectTheme(username, theme.id());
        return current(username);
    }

    public synchronized ThemeCatalog update(String username, String themeId, PlayerThemeRequest request) {
        requireTheme(themeId);
        PlayerThemeOption theme = toTheme(request, themeId);
        List<PlayerThemeOption> updated = themes.stream()
            .map(existing -> existing.id().equals(themeId) ? theme : existing)
            .toList();
        persist(updated, selectedThemeId);
        themes = updated;
        return current(username);
    }

    public synchronized ThemeCatalog delete(String username, String themeId) {
        requireTheme(themeId);
        if (themes.size() == 1) throw new IllegalArgumentException("At least one theme must remain.");
        List<PlayerThemeOption> updated = themes.stream().filter(theme -> !theme.id().equals(themeId)).toList();
        String nextSelectedId = selectedThemeId.equals(themeId) ? updated.getFirst().id() : selectedThemeId;
        persist(updated, nextSelectedId);
        themes = updated;
        selectedThemeId = nextSelectedId;
        userService.replaceDeletedTheme(themeId, nextSelectedId);
        return current(username);
    }

    private PlayerThemeOption toTheme(PlayerThemeRequest request, String id) {
        if (request == null) throw new IllegalArgumentException("Theme details are required.");
        return validate(new PlayerThemeOption(
            id,
            request.name(),
            request.description(),
            request.mode(),
            request.background(),
            request.surface(),
            request.text(),
            request.muted(),
            request.accent(),
            request.line(),
            request.backdrop(),
            request.visualizerPalette(),
            request.visualizerStyle(),
            request.visualizerBarCount(),
            request.visualizerSensitivity()
        ));
    }

    private PlayerThemeOption validate(PlayerThemeOption theme) {
        if (theme == null || theme.id() == null || !theme.id().matches("[a-z0-9][a-z0-9-]{0,39}")) {
            throw new IllegalArgumentException("Theme ID is invalid.");
        }
        if (theme.name() == null || theme.name().isBlank() || theme.name().length() > 48) {
            throw new IllegalArgumentException("Theme name must be between 1 and 48 characters.");
        }
        if (theme.description() == null || theme.description().isBlank() || theme.description().length() > 180) {
            throw new IllegalArgumentException("Theme description must be between 1 and 180 characters.");
        }
        if (theme.mode() == null || !MODES.contains(theme.mode())) {
            throw new IllegalArgumentException("Theme mode must be dark or light.");
        }
        for (String color : Stream.of(theme.background(), theme.surface(), theme.text(), theme.muted(), theme.accent(), theme.line()).toList()) {
            if (color == null || !HEX_COLOR.matcher(color).matches()) {
                throw new IllegalArgumentException("Theme colors must use six-digit hexadecimal values.");
            }
        }
        if (theme.backdrop() == null || !BACKDROPS.contains(theme.backdrop())) {
            throw new IllegalArgumentException("Backdrop must be aurora, halo, or plain.");
        }
        if (theme.visualizerPalette() == null || theme.visualizerPalette().size() != 3
            || theme.visualizerPalette().stream().anyMatch(color -> color == null || !HEX_COLOR.matcher(color).matches())) {
            throw new IllegalArgumentException("The visualizer palette must contain three six-digit hexadecimal colors.");
        }
        if (theme.visualizerStyle() == null || !VISUALIZER_STYLES.contains(theme.visualizerStyle())) {
            throw new IllegalArgumentException("Visualizer style must be bars, mirror, or wave.");
        }
        if (theme.visualizerBarCount() < 12 || theme.visualizerBarCount() > 96) {
            throw new IllegalArgumentException("Visualizer bar count must be between 12 and 96.");
        }
        if (!Double.isFinite(theme.visualizerSensitivity())
            || theme.visualizerSensitivity() < 0.25 || theme.visualizerSensitivity() > 2.0) {
            throw new IllegalArgumentException("Visualizer sensitivity must be between 0.25 and 2.0.");
        }
        return new PlayerThemeOption(
            theme.id(), theme.name().trim(), theme.description().trim(), theme.mode(),
            theme.background().toLowerCase(Locale.ROOT), theme.surface().toLowerCase(Locale.ROOT),
            theme.text().toLowerCase(Locale.ROOT), theme.muted().toLowerCase(Locale.ROOT),
            theme.accent().toLowerCase(Locale.ROOT), theme.line().toLowerCase(Locale.ROOT),
            theme.backdrop(), theme.visualizerPalette().stream().map(color -> color.toLowerCase(Locale.ROOT)).toList(),
            theme.visualizerStyle(), theme.visualizerBarCount(), theme.visualizerSensitivity()
        );
    }

    private String uniqueId(String name) {
        String base = name == null ? "" : name.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
        if (base.isBlank()) throw new IllegalArgumentException("Theme name must contain letters or numbers.");
        String id = base.substring(0, Math.min(base.length(), 40));
        int suffix = 2;
        while (idExists(id)) {
            String ending = "-" + suffix++;
            id = base.substring(0, Math.min(base.length(), 40 - ending.length())) + ending;
        }
        return id;
    }

    private boolean idExists(String id) {
        return themes.stream().anyMatch(theme -> theme.id().equals(id));
    }

    private PlayerThemeOption requireTheme(String themeId) {
        if (themeId == null) throw new IllegalArgumentException("Theme ID is required.");
        return themes.stream()
            .filter(theme -> theme.id().equals(themeId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown music player theme: " + themeId));
    }

    private void persist(List<PlayerThemeOption> updatedThemes, String selectedId) {
        Path temporaryFile = themeFile.resolveSibling(themeFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(themeFile.getParent());
            objectMapper.writeValue(temporaryFile.toFile(), new PlayerThemePreference(selectedId, updatedThemes));
            try {
                Files.move(temporaryFile, themeFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, themeFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save music player themes to " + themeFile, exception);
        }
    }

    private static List<PlayerThemeOption> defaultThemes() {
        return List.of(
            new PlayerThemeOption("midnight", "Midnight blue", "The calm, deep-blue Atalaya classic.", "dark",
                "#0b1220", "#111d31", "#e9f0fb", "#98abc6", "#94c8ff", "#243550", "aurora",
                List.of("#4edfff", "#a274ff", "#ff67ce"), "bars", 48, 1.0),
            new PlayerThemeOption("evergreen", "Evergreen", "A quiet forest palette with fresh sage highlights.", "dark",
                "#101a18", "#192722", "#edf2e8", "#aab9aa", "#b7d8a4", "#30453a", "halo",
                List.of("#b7d8a4", "#59c7a5", "#d6be7c"), "wave", 40, 0.9),
            new PlayerThemeOption("sunroom", "Sunroom", "Warm paper tones with a little amber and ink.", "light",
                "#f3eee3", "#e9e1d3", "#302a25", "#75695e", "#a65c2a", "#d2c5b2", "plain",
                List.of("#a65c2a", "#d29b4c", "#587c78"), "mirror", 36, 0.85),
            new PlayerThemeOption("afterhours", "Afterhours", "A velvet plum night with soft lilac light.", "dark",
                "#17121f", "#211a2b", "#f1eafa", "#b7a8c7", "#d6a8ff", "#40304e", "aurora",
                List.of("#4edfff", "#c458ff", "#ff68ab"), "bars", 56, 1.15)
        );
    }

    public record ThemeCatalog(String selectedThemeId, List<PlayerThemeOption> themes) {}
}
