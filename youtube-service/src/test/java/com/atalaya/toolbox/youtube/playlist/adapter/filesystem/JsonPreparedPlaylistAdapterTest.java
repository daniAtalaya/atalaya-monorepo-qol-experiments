package com.atalaya.toolbox.youtube.playlist.adapter.filesystem;

import com.atalaya.toolbox.youtube.playlist.adapter.JsonPreparedPlaylistAdapter;
import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import com.atalaya.toolbox.youtube.playlist.domain.PreparedPlaylist;
import com.atalaya.toolbox.youtube.playlist.domain.PreparedTrack;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonPreparedPlaylistAdapterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void removesOnlyTheCompletedTrackAndPersistsTheOriginalMetadata() {
        JsonPreparedPlaylistAdapter repository = new JsonPreparedPlaylistAdapter(
                new YoutubeProperties(temporaryDirectory), new ObjectMapper());
        PreparedTrack successfulTrack = new PreparedTrack(
                "video_123",
                "Available track",
                "https://www.youtube.com/watch?v=video_123",
                Map.of("id", "video_123", "duration", 180));
        PreparedTrack failedTrack = new PreparedTrack(
                "video_456",
                "Unavailable track",
                "https://www.youtube.com/watch?v=video_456",
                Map.of("id", "video_456", "duration", 210),
                "comercial/badbunny/epicas");
        PreparedPlaylist playlist = new PreparedPlaylist(
                "a".repeat(64),
                "https://www.youtube.com/playlist?list=playlist_123",
                "2026-09-29T10:00:00Z",
                2,
                List.of(successfulTrack, failedTrack), null);
        repository.save(playlist);

        repository.removeTrack(playlist.id(), successfulTrack.videoId());

        PreparedPlaylist saved = repository.findById(playlist.id()).orElseThrow();
        assertEquals(2, saved.totalTrackCount());
        assertEquals(List.of(failedTrack), saved.tracks());
        assertEquals("comercial/badbunny/epicas", saved.tracks().getFirst().destination());
        assertEquals(1, repository.findPendingIds().size());
        assertTrue(Files.exists(repository.filePath(playlist.id())));
    }

    @Test
    void namesPlaylistFileUsingYoutubeListId() {
        JsonPreparedPlaylistAdapter repository = new JsonPreparedPlaylistAdapter(
                new YoutubeProperties(temporaryDirectory), new ObjectMapper());
        PreparedPlaylist playlist = new PreparedPlaylist(
                "PL_ab-123",
                "https://www.youtube.com/playlist?list=PL_ab-123",
                "2026-09-29T10:00:00Z",
                1,
                List.of(new PreparedTrack(
                        "video_123", "Song", "https://youtube.com/watch?v=video_123", Map.of())), null);

        repository.save(playlist);

        assertEquals(temporaryDirectory.resolve("prepared-playlists/PL_ab-123.json"),
                repository.filePath(playlist.id()));
        assertTrue(Files.exists(repository.filePath(playlist.id())));
    }
}
