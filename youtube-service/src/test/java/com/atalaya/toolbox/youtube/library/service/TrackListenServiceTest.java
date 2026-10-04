package com.atalaya.toolbox.youtube.library.service;

import com.atalaya.toolbox.youtube.library.domain.MusicLibraryNode;
import com.atalaya.toolbox.youtube.library.repository.MusicLibraryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.atalaya.toolbox.youtube.users.service.MusicPlayerUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrackListenServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsListenCountsSeparateForEachUser() throws Exception {
        Path song = Files.createFile(temporaryDirectory.resolve("song.mp3"));
        Path other = Files.createFile(temporaryDirectory.resolve("other.mp3"));
        MusicPlayerUserService users = users(temporaryDirectory.resolve("users.json"));
        users.selectOrCreate("alice");
        users.selectOrCreate("bob");
        TrackListenService service = new TrackListenService(repository(song, other), users);

        service.recordListen("alice", "song.mp3");
        service.recordListen("alice", "other.mp3");
        service.recordListen("alice", "song.mp3");
        service.recordListen("bob", "other.mp3");

        MusicPlayerUserService restoredUsers = users(temporaryDirectory.resolve("users.json"));
        restoredUsers.loadUsers();
        TrackListenService restored = new TrackListenService(repository(song, other), restoredUsers);
        assertEquals(List.of("song.mp3", "other.mp3"), restored.mostListened("alice").stream()
            .map(track -> track.path()).toList());
        assertEquals(2, restored.mostListened("alice").getFirst().listenCount());
        assertEquals(1, restored.mostListened("bob").getFirst().listenCount());
    }

    @Test
    void rejectsMissingAndParentTraversalTracks() throws Exception {
        Path song = Files.createFile(temporaryDirectory.resolve("song.mp3"));
        MusicPlayerUserService users = users(temporaryDirectory.resolve("users.json"));
        users.selectOrCreate("alice");
        TrackListenService service = new TrackListenService(repository(song), users);

        assertThrows(IllegalArgumentException.class, () -> service.recordListen("alice", "../missing.mp3"));
        assertThrows(IllegalArgumentException.class, () -> service.recordListen("alice", "missing.mp3"));
    }

    private MusicPlayerUserService users(Path file) {
        return new MusicPlayerUserService(new ObjectMapper(), file.toString());
    }

    private static MusicLibraryRepository repository(Path... tracks) {
        return new MusicLibraryRepository() {
            @Override
            public List<MusicLibraryNode> findTree(String relativePath, Integer nestedFolderDepth) {
                return List.of();
            }

            @Override
            public Optional<Path> findTrack(String relativePath) {
                return java.util.Arrays.stream(tracks)
                    .filter(track -> track.getFileName().toString().equals(relativePath))
                    .findFirst();
            }
        };
    }
}
