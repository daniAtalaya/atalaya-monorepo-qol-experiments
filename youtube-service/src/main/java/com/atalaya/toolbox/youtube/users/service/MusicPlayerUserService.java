package com.atalaya.toolbox.youtube.users.service;

import com.atalaya.toolbox.youtube.library.domain.MostListenedTrack;
import com.atalaya.toolbox.youtube.users.domain.MusicPlayerUser;
import com.atalaya.toolbox.youtube.users.domain.MusicPlayerUserProfile;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class MusicPlayerUserService {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,31}");

    private final ObjectMapper objectMapper;
    private final Path usersFile;
    private final Map<String, ProfileData> profiles = new HashMap<>();

    public MusicPlayerUserService(
        ObjectMapper objectMapper,
        @Value("${toolbox.music-player.users-file:.data/music-player-users.json}") String usersFile
    ) {
        this.objectMapper = objectMapper;
        try {
            this.usersFile = Path.of(System.getProperty("user.dir")).resolve(usersFile).normalize();
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Music player users file path is invalid.", exception);
        }
    }

    @PostConstruct
    public synchronized void loadUsers() {
        if (!Files.exists(usersFile)) return;
        try {
            List<MusicPlayerUserProfile> savedProfiles = objectMapper.readValue(
                usersFile.toFile(),
                new TypeReference<>() {}
            );
            for (MusicPlayerUserProfile profile : savedProfiles) {
                String username = normalizeUsername(profile.username());
                if (profiles.containsKey(username)) throw new IllegalArgumentException("Duplicate music player username.");
                ProfileData data = new ProfileData(username, profile.selectedThemeId());
                for (MostListenedTrack track : profile.listens()) {
                    data.listens.put(track.path(), track);
                }
                profiles.put(username, data);
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not load music player users from " + usersFile, exception);
        }
    }

    public synchronized List<MusicPlayerUser> listUsers() {
        return profiles.values().stream()
            .map(profile -> new MusicPlayerUser(profile.username))
            .sorted(Comparator.comparing(MusicPlayerUser::username, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    public synchronized MusicPlayerUser selectOrCreate(String requestedUsername) {
        String username = normalizeUsername(requestedUsername);
        profiles.computeIfAbsent(username, key -> new ProfileData(key, "midnight"));
        persist();
        return new MusicPlayerUser(username);
    }

    public synchronized void selectTheme(String username, String themeId) {
        ProfileData profile = requireProfile(username);
        profile.selectedThemeId = themeId;
        persist();
    }

    public synchronized void replaceDeletedTheme(String deletedThemeId, String fallbackThemeId) {
        boolean changed = false;
        for (ProfileData profile : profiles.values()) {
            if (deletedThemeId.equals(profile.selectedThemeId)) {
                profile.selectedThemeId = fallbackThemeId;
                changed = true;
            }
        }
        if (changed) persist();
    }

    public synchronized String selectedTheme(String username, String fallback) {
        return requireProfile(username).selectedThemeId == null
            ? fallback
            : requireProfile(username).selectedThemeId;
    }

    public synchronized void setListens(String username, List<MostListenedTrack> listens) {
        ProfileData profile = requireProfile(username);
        Map<String, MostListenedTrack> updated = new HashMap<>();
        for (MostListenedTrack track : listens) updated.put(track.path(), track);
        profile.listens.clear();
        profile.listens.putAll(updated);
        persist();
    }

    public synchronized List<MostListenedTrack> listens(String username) {
        return requireProfile(username).listens.values().stream()
            .sorted(Comparator.comparingLong(MostListenedTrack::listenCount).reversed()
                .thenComparing(MostListenedTrack::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private ProfileData requireProfile(String requestedUsername) {
        String username = normalizeUsername(requestedUsername);
        ProfileData profile = profiles.get(username);
        if (profile == null) throw new IllegalArgumentException("Unknown music player username.");
        return profile;
    }

    private String normalizeUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username.trim()).matches()) {
            throw new IllegalArgumentException("Username must be 1-32 letters, numbers, underscores, or hyphens.");
        }
        return username.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void persist() {
        Path temporaryFile = usersFile.resolveSibling(usersFile.getFileName() + ".tmp");
        List<MusicPlayerUserProfile> savedProfiles = profiles.values().stream()
            .map(profile -> new MusicPlayerUserProfile(profile.username, profile.selectedThemeId,
                new ArrayList<>(profile.listens.values())))
            .sorted(Comparator.comparing(MusicPlayerUserProfile::username))
            .toList();
        try {
            Files.createDirectories(usersFile.getParent());
            objectMapper.writeValue(temporaryFile.toFile(), savedProfiles);
            try {
                Files.move(temporaryFile, usersFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, usersFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save music player users to " + usersFile, exception);
        }
    }

    private static final class ProfileData {
        private final String username;
        private String selectedThemeId;
        private final Map<String, MostListenedTrack> listens = new HashMap<>();

        private ProfileData(String username, String selectedThemeId) {
            this.username = username;
            this.selectedThemeId = selectedThemeId;
        }
    }
}
