package com.atalaya.toolbox.youtube.history.service;

import com.atalaya.toolbox.youtube.history.repository.DownloadHistoryRepository;
import com.atalaya.toolbox.youtube.playlist.scheduler.PreparedPlaylistJobScheduler;
import com.atalaya.toolbox.youtube.playlist.repository.PreparedPlaylistRepository;
import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import com.atalaya.toolbox.youtube.history.domain.DownloadHistoryEntry;
import com.atalaya.toolbox.youtube.playlist.domain.PreparedPlaylist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReconcileMissingHistoryServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void queuesOnlyUniqueHistoryTracksMissingFromTheMusicDirectory() throws Exception {
        Path musicDirectory = Files.createDirectories(temporaryDirectory.resolve("music"));
        Files.createFile(musicDirectory.resolve("Present Song --- present_123.mp3"));
        DownloadHistoryRepository historyRepository = mock(DownloadHistoryRepository.class);
        when(historyRepository.findAll()).thenReturn(List.of(
                historyEntry("present_123", musicDirectory.resolve("Present Song --- present_123.mp3")),
                historyEntry("missing_123"),
                historyEntry("missing_123")));
        InMemoryPlaylistRepository playlistRepository = new InMemoryPlaylistRepository();
        PreparedPlaylistJobScheduler scheduler = mock(PreparedPlaylistJobScheduler.class);
        ReconcileMissingHistoryService service = new ReconcileMissingHistoryService(
                historyRepository,
                playlistRepository,
                scheduler,
                new YoutubeProperties(temporaryDirectory));

        var result = service.reconcile();

        assertEquals(2, result.historyVideoCount());
        assertEquals(1, result.presentTrackCount());
        assertEquals(1, result.missingTrackCount());
        assertEquals(1, result.queuedTrackCount());
        assertEquals(1, playlistRepository.findAll().getFirst().tracks().size());
        assertEquals("missing_123", playlistRepository.findAll().getFirst().tracks().getFirst().videoId());
        verify(scheduler).schedule(result.playlistId());
    }

    @Test
    void discoversExistingTracksInNestedMusicDirectories() throws Exception {
        Path nestedDirectory = Files.createDirectories(
                temporaryDirectory.resolve("music/pokemon/rejuvenation"));
        Files.createFile(nestedDirectory.resolve("Nested Song --- nested_123.mp3"));
        DownloadHistoryRepository historyRepository = mock(DownloadHistoryRepository.class);
        when(historyRepository.findAll()).thenReturn(List.of(
            historyEntry("nested_123", nestedDirectory.resolve("Nested Song --- nested_123.mp3"))));
        InMemoryPlaylistRepository playlistRepository = new InMemoryPlaylistRepository();
        PreparedPlaylistJobScheduler scheduler = mock(PreparedPlaylistJobScheduler.class);
        ReconcileMissingHistoryService service = new ReconcileMissingHistoryService(
                historyRepository,
                playlistRepository,
                scheduler,
                new YoutubeProperties(temporaryDirectory));

        var result = service.reconcile();

        assertEquals(1, result.presentTrackCount());
        assertEquals(0, result.missingTrackCount());
        verifyNoInteractions(scheduler);
    }

    @Test
    void queuesMissingTrackToTheFolderRecordedInDownloadHistory() {
        Path missingTrack = temporaryDirectory.resolve("music/comercial/badbunny/epicas/Bad Bunny Song --- missing_456.mp3");
        DownloadHistoryEntry entry = new DownloadHistoryEntry(
            "missing_456",
            "Bad Bunny Song",
            "https://www.youtube.com/watch?v=missing_456",
            "https://www.youtube.com/playlist?list=playlist_123",
            "2026-09-29T10:00:00Z",
            missingTrack.toString(),
            1000);
        DownloadHistoryRepository historyRepository = mock(DownloadHistoryRepository.class);
        when(historyRepository.findAll()).thenReturn(List.of(entry));
        InMemoryPlaylistRepository playlistRepository = new InMemoryPlaylistRepository();
        PreparedPlaylistJobScheduler scheduler = mock(PreparedPlaylistJobScheduler.class);
        ReconcileMissingHistoryService service = new ReconcileMissingHistoryService(
            historyRepository,
            playlistRepository,
            scheduler,
            new YoutubeProperties(temporaryDirectory));

        var result = service.reconcile();

        assertEquals("comercial" + java.io.File.separator + "badbunny" + java.io.File.separator + "epicas",
            playlistRepository.findById(result.playlistId()).orElseThrow().tracks().getFirst().destination());
    }

    private DownloadHistoryEntry historyEntry(String videoId) {
        return historyEntry(videoId, temporaryDirectory.resolve("music/Song " + videoId + " --- " + videoId + ".mp3"));
    }

    private DownloadHistoryEntry historyEntry(String videoId, Path file) {
        return new DownloadHistoryEntry(
                videoId,
                "Song " + videoId,
                "https://www.youtube.com/watch?v=" + videoId,
                "https://www.youtube.com/playlist?list=playlist_123",
                "2026-09-29T10:00:00Z",
                file.toString(),
                1000);
    }

    private static class InMemoryPlaylistRepository implements PreparedPlaylistRepository {

        private final List<PreparedPlaylist> playlists = new ArrayList<>();

        @Override
        public Optional<PreparedPlaylist> findById(String id) {
            return playlists.stream().filter(playlist -> playlist.id().equals(id)).findFirst();
        }

        @Override
        public List<PreparedPlaylist> findAll() {
            return List.copyOf(playlists);
        }

        @Override
        public List<String> findPendingIds() {
            return playlists.stream().filter(playlist -> !playlist.tracks().isEmpty())
                    .map(PreparedPlaylist::id).toList();
        }

        @Override
        public void save(PreparedPlaylist playlist) {
            playlists.removeIf(existing -> existing.id().equals(playlist.id()));
            playlists.add(playlist);
        }

        @Override
        public void removeTrack(String playlistId, String videoId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path filePath(String playlistId) {
            return Path.of(playlistId + ".json");
        }
    }
}
