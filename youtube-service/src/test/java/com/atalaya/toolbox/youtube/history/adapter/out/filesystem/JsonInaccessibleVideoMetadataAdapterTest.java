package com.atalaya.toolbox.youtube.history.adapter.out.filesystem;

import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import com.atalaya.toolbox.youtube.history.adapter.JsonInaccessibleVideoMetadataAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonInaccessibleVideoMetadataAdapterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void accumulatesObservationsForTheSameVideoInAnIsolatedJsonFile() {
        JsonInaccessibleVideoMetadataAdapter repository = new JsonInaccessibleVideoMetadataAdapter(
                new YoutubeProperties(temporaryDirectory), new ObjectMapper());

        repository.recordFailure("video_123", "https://youtube.com/playlist?list=abc", "missing title");
        repository.recordFailure("video_123", "https://youtube.com/playlist?list=abc", "unavailable");

        var recorded = repository.findAll().getFirst();
        assertEquals("video_123", recorded.videoId());
        assertEquals(2, recorded.observations());
        assertEquals("unavailable", recorded.latestReason());
        assertEquals(1, recorded.sourceUrls().size());
        assertTrue(Files.exists(temporaryDirectory.resolve("history/inaccessible-video-metadata.json")));

        repository.recordSuccess("video_123");

        assertEquals(0, repository.findAll().size());
    }

    @Test
    void migratesExistingRegistryIntoPersistentWorkDirectory() throws Exception {
        Path oldFile = temporaryDirectory.resolve("inaccessible-video-metadata.json");
        Files.writeString(oldFile, "[]");
        JsonInaccessibleVideoMetadataAdapter repository = new JsonInaccessibleVideoMetadataAdapter(
                new YoutubeProperties(temporaryDirectory), new ObjectMapper());

        assertEquals(0, repository.findAll().size());

        assertTrue(Files.exists(temporaryDirectory.resolve("history/inaccessible-video-metadata.json")));
        assertTrue(Files.notExists(oldFile));
    }

    @Test
    void migratesRegistryFromWorkDirectoryIntoHistoryDirectory() throws Exception {
        Path oldWorkFile = temporaryDirectory.resolve("work/inaccessible-video-metadata.json");
        Files.createDirectories(oldWorkFile.getParent());
        Files.writeString(oldWorkFile, "[]");
        JsonInaccessibleVideoMetadataAdapter repository = new JsonInaccessibleVideoMetadataAdapter(
                new YoutubeProperties(temporaryDirectory), new ObjectMapper());

        assertEquals(0, repository.findAll().size());

        assertTrue(Files.exists(temporaryDirectory.resolve("history/inaccessible-video-metadata.json")));
        assertTrue(Files.notExists(oldWorkFile));
    }

    @Test
    void deletesAnEntryAndKeepsTheRegistryAvailableAfterClearingIt() throws Exception {
        JsonInaccessibleVideoMetadataAdapter repository = new JsonInaccessibleVideoMetadataAdapter(
                new YoutubeProperties(temporaryDirectory), new ObjectMapper());
        Path metadataFile = temporaryDirectory.resolve("history/inaccessible-video-metadata.json");

        repository.recordFailure("video_to_delete", "https://youtube.com/watch?v=video_to_delete", "unavailable");
        assertTrue(repository.delete("video_to_delete"));
        assertFalse(repository.delete("video_to_delete"));
        assertEquals(0, repository.findAll().size());

        repository.recordFailure("video_to_clear", "https://youtube.com/watch?v=video_to_clear", "unavailable");
        repository.clear();
        assertTrue(Files.exists(metadataFile));
        assertEquals(0, new ObjectMapper().readTree(metadataFile.toFile()).size());
        assertEquals(0, repository.findAll().size());

        repository.recordFailure("video_after_clear", "https://youtube.com/watch?v=video_after_clear", "unavailable");
        assertEquals("video_after_clear", repository.findAll().getFirst().videoId());
    }
}
