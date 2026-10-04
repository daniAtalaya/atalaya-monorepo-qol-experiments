package com.atalaya.toolbox.youtube.history.adapter.out.filesystem;

import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import com.atalaya.toolbox.youtube.history.adapter.JsonDownloadHistoryAdapter;
import com.atalaya.toolbox.youtube.history.domain.DownloadHistoryEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonDownloadHistoryAdapterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesEachUtcDayInItsOwnJsonFile() throws Exception {
        JsonDownloadHistoryAdapter adapter = new JsonDownloadHistoryAdapter(
                new YoutubeProperties(temporaryDirectory), new ObjectMapper());
        DownloadHistoryEntry entry = new DownloadHistoryEntry(
                "video_123",
                "Canción de prueba",
                "https://www.youtube.com/watch?v=video_123",
                "https://www.youtube.com/playlist?list=playlist_123",
                "2026-09-29T10:00:00Z",
                temporaryDirectory.resolve("music/Canción de prueba --- video_123.mp3").toString(),
                1250);
        DownloadHistoryEntry secondEntry = new DownloadHistoryEntry(
                "video_456",
                "Otra canción",
                "https://www.youtube.com/watch?v=video_456",
                "https://www.youtube.com/playlist?list=playlist_123",
                "2026-09-29T10:15:00Z",
                temporaryDirectory.resolve("music/Otra canción --- video_456.mp3").toString(),
                2400);
        DownloadHistoryEntry nextDayEntry = new DownloadHistoryEntry(
                "video_789",
                "Canción del día siguiente",
                "https://www.youtube.com/watch?v=video_789",
                "https://www.youtube.com/watch?v=video_789",
                "2026-09-30T00:05:00Z",
                temporaryDirectory.resolve("music/Canción del día siguiente --- video_789.mp3").toString(),
                3100);

        adapter.save(entry);
        adapter.save(secondEntry);
        adapter.save(nextDayEntry);

        Path historyDirectory = temporaryDirectory.resolve("history");
        Path firstDayFile = historyDirectory.resolve("2026-09-29.json");
        Path secondDayFile = historyDirectory.resolve("2026-09-30.json");
        JsonNode firstDayJson = new ObjectMapper().readTree(firstDayFile.toFile());
        JsonNode secondDayJson = new ObjectMapper().readTree(secondDayFile.toFile());
        assertEquals(2, firstDayJson.size());
        assertEquals(1, secondDayJson.size());
        assertEquals(List.of(nextDayEntry, secondEntry, entry), adapter.findAll());
        try (var historyFiles = Files.list(historyDirectory)) {
            assertEquals(2, historyFiles.filter(path -> path.toString().endsWith(".json")).count());
        }
        assertTrue(firstDayJson.get(0).has("downloadTimeMillis"));
    }

    @Test
    void replacesEveryOlderEntryForTheSameVideoAcrossHistoryDays() {
        JsonDownloadHistoryAdapter adapter = new JsonDownloadHistoryAdapter(
            new YoutubeProperties(temporaryDirectory), new ObjectMapper());
        DownloadHistoryEntry olderDuplicate = new DownloadHistoryEntry(
            "video_123", "Old title", "https://www.youtube.com/watch?v=video_123",
            "old-playlist", "2026-09-28T10:00:00Z", "old-location.mp3", 1250);
        DownloadHistoryEntry anotherOlderDuplicate = new DownloadHistoryEntry(
            "video_123", "Old title", "https://www.youtube.com/watch?v=video_123",
            "another-playlist", "2026-09-29T10:00:00Z", "another-location.mp3", 1400);
        DownloadHistoryEntry unrelatedEntry = new DownloadHistoryEntry(
            "video_456", "Other song", "https://www.youtube.com/watch?v=video_456",
            "playlist", "2026-09-29T11:00:00Z", "other-song.mp3", 900);
        DownloadHistoryEntry reconciledEntry = new DownloadHistoryEntry(
            "video_123", "Current title", "https://www.youtube.com/watch?v=video_123",
            "history-reconciliation", "2026-09-30T12:00:00Z", "restored-location.mp3", 1800);
        adapter.save(olderDuplicate);
        adapter.save(anotherOlderDuplicate);
        adapter.save(unrelatedEntry);

        adapter.replaceByVideoId(reconciledEntry);

        assertEquals(List.of(reconciledEntry, unrelatedEntry), adapter.findAll());
    }

    @Test
    void migratesExistingPerTrackFilesIntoTheMatchingDayFile() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Path historyDirectory = Files.createDirectories(temporaryDirectory.resolve("history"));
        DownloadHistoryEntry legacyEntry = new DownloadHistoryEntry(
                "legacy_123",
                "Canción antigua",
                "https://www.youtube.com/watch?v=legacy_123",
                "https://www.youtube.com/watch?v=legacy_123",
                "2026-09-28T10:00:00Z",
                temporaryDirectory.resolve("music/legacy_123.mp3").toString(),
                800);
        objectMapper.writeValue(historyDirectory.resolve("legacy_123.json").toFile(), legacyEntry);
        JsonDownloadHistoryAdapter adapter = new JsonDownloadHistoryAdapter(
                new YoutubeProperties(temporaryDirectory), objectMapper);

        assertEquals(List.of(legacyEntry), adapter.findAll());

        assertTrue(Files.exists(historyDirectory.resolve("2026-09-28.json")));
        assertTrue(Files.notExists(historyDirectory.resolve("legacy_123.json")));
        try (var historyFiles = Files.list(historyDirectory)) {
            assertEquals(1, historyFiles
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .count());
        }
    }

    @Test
    void doesNotTreatInaccessibleMetadataArrayAsLegacyDownloadEntry() throws Exception {
        Path historyDirectory = Files.createDirectories(temporaryDirectory.resolve("history"));
        Path inaccessibleMetadataFile = historyDirectory.resolve("inaccessible-video-metadata.json");
        Files.writeString(inaccessibleMetadataFile, "[{\"videoId\":\"video_123\"}]");
        DownloadHistoryEntry entry = new DownloadHistoryEntry(
            "download_123",
            "Downloaded song",
            "https://www.youtube.com/watch?v=download_123",
            "https://www.youtube.com/watch?v=download_123",
            "2026-09-29T10:00:00Z",
            temporaryDirectory.resolve("music/download_123.mp3").toString(),
            800
        );
        new ObjectMapper().writeValue(historyDirectory.resolve("download-history.json").toFile(),
            java.util.Map.of("2026-09-29", List.of(entry)));
        JsonDownloadHistoryAdapter adapter = new JsonDownloadHistoryAdapter(
            new YoutubeProperties(temporaryDirectory), new ObjectMapper());

        assertEquals(List.of(entry), adapter.findAll());
        assertTrue(Files.exists(inaccessibleMetadataFile));
        assertEquals("[{\"videoId\":\"video_123\"}]", Files.readString(inaccessibleMetadataFile));
        assertTrue(Files.exists(historyDirectory.resolve("2026-09-29.json")));
        assertTrue(Files.notExists(historyDirectory.resolve("download-history.json")));
    }

    @Test
    void migratesExistingGroupedHistoryIntoSeparateUtcDayFiles() throws Exception {
        Path historyDirectory = Files.createDirectories(temporaryDirectory.resolve("history"));
        DownloadHistoryEntry firstDayEntry = new DownloadHistoryEntry(
            "day_one", "First day", "https://www.youtube.com/watch?v=day_one", "playlist",
            "2026-09-28T23:00:00Z", "first.mp3", 500);
        DownloadHistoryEntry secondDayEntry = new DownloadHistoryEntry(
            "day_two", "Second day", "https://www.youtube.com/watch?v=day_two", "playlist",
            "2026-09-29T01:00:00Z", "second.mp3", 600);
        new ObjectMapper().writeValue(historyDirectory.resolve("download-history.json").toFile(),
            java.util.Map.of("2026-09-28", List.of(firstDayEntry), "2026-09-29", List.of(secondDayEntry)));
        JsonDownloadHistoryAdapter adapter = new JsonDownloadHistoryAdapter(
            new YoutubeProperties(temporaryDirectory), new ObjectMapper());

        assertEquals(List.of(secondDayEntry, firstDayEntry), adapter.findAll());

        assertEquals(1, new ObjectMapper().readTree(historyDirectory.resolve("2026-09-28.json").toFile()).size());
        assertEquals(1, new ObjectMapper().readTree(historyDirectory.resolve("2026-09-29.json").toFile()).size());
        assertTrue(Files.notExists(historyDirectory.resolve("download-history.json")));
    }
}
