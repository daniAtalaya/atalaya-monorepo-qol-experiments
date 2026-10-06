package com.atalaya.toolbox.preferences.service;

import com.atalaya.toolbox.preferences.domain.MostListenedTrack;
import com.atalaya.toolbox.preferences.domain.PlayerSettings;
import com.atalaya.toolbox.preferences.domain.UserProfile;
import com.atalaya.toolbox.preferences.domain.UserProfileSummary;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class UserPreferencesService {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,31}");

    private final ObjectMapper objectMapper;
    private final Path usersFile;
    private final Map<String, UserProfile> profiles = new HashMap<>();

    public UserPreferencesService(ObjectMapper objectMapper, @Value("${toolbox.preferences.storage-directory}") String storageDirectory) {
        this.objectMapper = objectMapper;
        Path root = Path.of(System.getProperty("user.dir"));
        this.usersFile = root.resolve(storageDirectory).normalize().resolve("users.json");
    }

    @PostConstruct
    public synchronized void loadProfiles() {
        profiles.clear();
        if (Files.exists(usersFile)) {
            loadProfileFile(usersFile);
            return;
        }
        if (!profiles.isEmpty()) persist();
    }

    public synchronized List<UserProfileSummary> listUsers() {
        return profiles.values().stream()
            .map(profile -> new UserProfileSummary(profile.username()))
            .sorted(Comparator.comparing(UserProfileSummary::username, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    public synchronized UserProfileSummary selectOrCreate(String requestedUsername) {
        String username = normalizeUsername(requestedUsername);
        profiles.computeIfAbsent(username, key -> new UserProfile(key, "midnight", List.of(), null));
        persist();
        return new UserProfileSummary(username);
    }

    public synchronized String selectedTheme(String username, String fallback) {
        UserProfile profile = requireProfile(username);
        return profile.selectedThemeId() == null ? fallback : profile.selectedThemeId();
    }

    public synchronized void selectTheme(String username, String themeId) {
        UserProfile profile = requireProfile(username);
        replace(profile, new UserProfile(profile.username(), themeId, profile.listens(), profile.volume()));
    }

    public synchronized void replaceDeletedTheme(String deletedThemeId, String fallbackThemeId) {
        boolean changed = false;
        for (UserProfile profile : List.copyOf(profiles.values())) {
            if (deletedThemeId.equals(profile.selectedThemeId())) {
                profiles.put(profile.username(), new UserProfile(profile.username(), fallbackThemeId, profile.listens(), profile.volume()));
                changed = true;
            }
        }
        if (changed) persist();
    }

    public synchronized List<MostListenedTrack> listens(String username) {
        return requireProfile(username).listens().stream()
            .sorted(Comparator.comparingLong(MostListenedTrack::listenCount).reversed()
                .thenComparing(MostListenedTrack::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    public synchronized MostListenedTrack recordListen(String username, String requestedPath) {
        String path = normalizeTrackPath(requestedPath);
        UserProfile profile = requireProfile(username);
        Map<String, MostListenedTrack> listens = new HashMap<>();
        for (MostListenedTrack track : profile.listens()) listens.put(track.path(), track);
        MostListenedTrack previous = listens.get(path);
        String name = path.substring(path.lastIndexOf('/') + 1);
        MostListenedTrack updated = new MostListenedTrack(
            path,
            name,
            previous == null ? 1 : Math.incrementExact(previous.listenCount()),
            Instant.now().toString()
        );
        listens.put(path, updated);
        replace(profile, new UserProfile(profile.username(), profile.selectedThemeId(), new ArrayList<>(listens.values()), profile.volume()));
        return updated;
    }

    public synchronized PlayerSettings playerSettings(String username) {
        return new PlayerSettings(requireProfile(username).volume());
    }

    public synchronized PlayerSettings updatePlayerSettings(String username, double volume) {
        if (!Double.isFinite(volume) || volume < 0 || volume > 1) {
            throw new IllegalArgumentException("Player volume must be between 0 and 1.");
        }
        UserProfile profile = requireProfile(username);
        replace(profile, new UserProfile(profile.username(), profile.selectedThemeId(), profile.listens(), volume));
        return new PlayerSettings(volume);
    }

    private void loadProfileFile(Path file) {
        try {
            List<UserProfile> savedProfiles = objectMapper.readValue(file.toFile(), new TypeReference<>() {});
            for (UserProfile saved : savedProfiles) {
                String username = normalizeUsername(saved.username());
                UserProfile normalized = new UserProfile(username, saved.selectedThemeId(),
                    saved.listens() == null ? List.of() : saved.listens(), saved.volume());
                profiles.merge(username, normalized, UserPreferencesService::mergeProfiles);
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not load user preferences from " + file, exception);
        }
    }

    private static UserProfile mergeProfiles(UserProfile current, UserProfile legacy) {
        Map<String, MostListenedTrack> listens = new HashMap<>();
        for (MostListenedTrack track : current.listens()) listens.put(track.path(), track);
        for (MostListenedTrack track : legacy.listens()) {
            listens.merge(track.path(), track, (left, right) -> left.listenCount() >= right.listenCount() ? left : right);
        }
        return new UserProfile(current.username(),
            current.selectedThemeId() == null ? legacy.selectedThemeId() : current.selectedThemeId(),
            new ArrayList<>(listens.values()), current.volume() == null ? legacy.volume() : current.volume());
    }

    private void replace(UserProfile profile, UserProfile updated) {
        profiles.put(profile.username(), updated);
        persist();
    }

    private UserProfile requireProfile(String requestedUsername) {
        String username = normalizeUsername(requestedUsername);
        UserProfile profile = profiles.get(username);
        if (profile == null) throw new IllegalArgumentException("Unknown username.");
        return profile;
    }

    private String normalizeUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username.trim()).matches()) {
            throw new IllegalArgumentException("Username must be 1-32 letters, numbers, underscores, or hyphens.");
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeTrackPath(String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank()) throw new IllegalArgumentException("Track path is required.");
        try {
            Path relative = Path.of(requestedPath.replace('/', java.io.File.separatorChar));
            if (relative.isAbsolute() || relative.normalize().startsWith("..")) {
                throw new IllegalArgumentException("Track path must be relative to the music library.");
            }
            return relative.normalize().toString().replace('\\', '/');
        } catch (java.nio.file.InvalidPathException exception) {
            throw new IllegalArgumentException("Track path is invalid.", exception);
        }
    }

    private void persist() {
        Path temporary = usersFile.resolveSibling(usersFile.getFileName() + ".tmp");
        List<UserProfile> data = profiles.values().stream()
            .sorted(Comparator.comparing(UserProfile::username))
            .toList();
        try {
            Files.createDirectories(usersFile.getParent());
            objectMapper.writeValue(temporary.toFile(), data);
            moveAtomically(temporary, usersFile);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save user preferences to " + usersFile, exception);
        }
    }

    private void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
