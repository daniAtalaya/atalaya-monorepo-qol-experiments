package com.atalaya.toolbox.youtube.library.service;

import com.atalaya.toolbox.youtube.library.domain.MostListenedTrack;
import com.atalaya.toolbox.youtube.library.repository.MusicLibraryRepository;
import com.atalaya.toolbox.youtube.users.service.MusicPlayerUserService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TrackListenService {
    private final MusicLibraryRepository libraryRepository;
    private final MusicPlayerUserService userService;

    public TrackListenService(MusicLibraryRepository libraryRepository, MusicPlayerUserService userService) {
        this.libraryRepository = libraryRepository;
        this.userService = userService;
    }

    public synchronized MostListenedTrack recordListen(String username, String requestedPath) {
        String path = normalizePath(requestedPath);
        var file = libraryRepository.findTrack(path)
            .orElseThrow(() -> new IllegalArgumentException("Track does not exist in the music library."));
        List<MostListenedTrack> current = userService.listens(username);
        Map<String, MostListenedTrack> updated = new HashMap<>();
        for (MostListenedTrack track : current) updated.put(track.path(), track);
        MostListenedTrack previous = updated.get(path);
        MostListenedTrack listenedTrack = new MostListenedTrack(
            path,
            file.getFileName().toString(),
            previous == null ? 1 : Math.incrementExact(previous.listenCount()),
            Instant.now().toString()
        );
        updated.put(path, listenedTrack);
        userService.setListens(username, new ArrayList<>(updated.values()));
        return listenedTrack;
    }

    public List<MostListenedTrack> mostListened(String username) {
        return userService.listens(username);
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("Track path is required.");
        try {
            java.nio.file.Path relativePath = java.nio.file.Path.of(path.replace('/', java.io.File.separatorChar));
            if (relativePath.isAbsolute() || relativePath.normalize().startsWith("..")) {
                throw new IllegalArgumentException("Track path must be relative to the music library.");
            }
            return relativePath.normalize().toString().replace('\\', '/');
        } catch (java.nio.file.InvalidPathException exception) {
            throw new IllegalArgumentException("Track path is invalid.", exception);
        }
    }
}
