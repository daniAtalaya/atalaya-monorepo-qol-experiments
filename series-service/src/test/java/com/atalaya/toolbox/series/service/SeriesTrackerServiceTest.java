package com.atalaya.toolbox.series.service;

import com.atalaya.toolbox.series.domain.EpisodeWatchRequest;
import com.atalaya.toolbox.series.domain.SeriesShow;
import com.atalaya.toolbox.series.domain.SeriesShowRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeriesTrackerServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void persistsSeriesAndEpisodeHistorySeparatelyPerUsername() {
        SeriesTrackerService service = new SeriesTrackerService(new ObjectMapper(), tempDirectory.toString());
        service.listShows("Alex");
        service.listShows("Sam");
        SeriesShow created = service.createShow("alex", request("Orbit House"));

        service.updateEpisode("alex", created.id(), new EpisodeWatchRequest(1, 3, "The long way home", true, "Perfect ending."));
        SeriesShow updated = service.updateShow("alex", created.id(), request("Orbit House, remastered"));

        SeriesTrackerService restarted = new SeriesTrackerService(new ObjectMapper(), tempDirectory.toString());
        SeriesShow persisted = restarted.listShows("alex").getFirst();
        assertEquals("Orbit House, remastered", persisted.title());
        assertEquals(1, persisted.watchedEpisodes().size());
        assertEquals("The long way home", persisted.watchedEpisodes().getFirst().title());
        assertEquals("Perfect ending.", persisted.watchedEpisodes().getFirst().notes());
        assertEquals(List.of(), restarted.listShows("sam"));
        assertEquals(updated.updatedAt(), persisted.updatedAt());
        assertEquals(List.of(), restarted.listShows("sam"));
    }

    @Test
    void migratesLegacyUsersDirectoryProfileToSeriesStorageRoot() throws IOException {
        SeriesTrackerService originalService = new SeriesTrackerService(new ObjectMapper(), tempDirectory.toString());
        SeriesShow originalShow = originalService.createShow("alex", request("Orbit House"));
        Path legacyDirectory = Files.createDirectories(tempDirectory.resolve("users"));
        Path legacyProfile = legacyDirectory.resolve("alex.json");
        Files.move(tempDirectory.resolve("alex.json"), legacyProfile);
        SeriesTrackerService service = new SeriesTrackerService(new ObjectMapper(), tempDirectory.toString());

        assertEquals(originalShow.title(), service.listShows("alex").getFirst().title());

        assertTrue(Files.isRegularFile(tempDirectory.resolve("alex.json")));
        assertFalse(Files.exists(legacyProfile));
        assertFalse(Files.exists(legacyDirectory));
    }

    @Test
    void removesAnEpisodeWhenItIsMarkedUnwatched() {
        SeriesTrackerService service = new SeriesTrackerService(new ObjectMapper(), tempDirectory.toString());
        service.listShows("alex");
        SeriesShow created = service.createShow("alex", request("Orbit House"));
        service.updateEpisode("alex", created.id(), new EpisodeWatchRequest(0, 1, "Special", true, null));

        SeriesShow updated = service.updateEpisode("alex", created.id(), new EpisodeWatchRequest(0, 1, null, false, null));

        assertEquals(List.of(), updated.watchedEpisodes());
    }

    @Test
    void editsEpisodeDetailsAndIdentityWithoutChangingWatchedDate() {
        SeriesTrackerService service = new SeriesTrackerService(new ObjectMapper(), tempDirectory.toString());
        SeriesShow created = service.createShow("alex", request("Orbit House"));
        SeriesShow logged = service.updateEpisode("alex", created.id(),
            new EpisodeWatchRequest(1, 2, "Original title", true, "Original note."));
        String watchedAt = logged.watchedEpisodes().getFirst().watchedAt();

        SeriesShow edited = service.updateEpisode("alex", created.id(),
            new EpisodeWatchRequest(2, 1, "Revised title", true, "Revised note.", 1, 2));

        assertEquals(1, edited.watchedEpisodes().size());
        assertEquals(2, edited.watchedEpisodes().getFirst().season());
        assertEquals(1, edited.watchedEpisodes().getFirst().episode());
        assertEquals("Revised title", edited.watchedEpisodes().getFirst().title());
        assertEquals("Revised note.", edited.watchedEpisodes().getFirst().notes());
        assertEquals(watchedAt, edited.watchedEpisodes().getFirst().watchedAt());
    }

    @Test
    void rejectsMovingAnEpisodeOntoAnAlreadyLoggedEpisode() {
        SeriesTrackerService service = new SeriesTrackerService(new ObjectMapper(), tempDirectory.toString());
        SeriesShow created = service.createShow("alex", request("Orbit House"));
        service.updateEpisode("alex", created.id(), new EpisodeWatchRequest(1, 1, null, true, null));
        service.updateEpisode("alex", created.id(), new EpisodeWatchRequest(1, 2, null, true, null));

        assertThrows(IllegalArgumentException.class, () -> service.updateEpisode("alex", created.id(),
            new EpisodeWatchRequest(1, 1, "Collision", true, null, 1, 2)));
    }

    @Test
    void rejectsInvalidSchedulesAndUnknownProfileData() {
        SeriesTrackerService service = new SeriesTrackerService(new ObjectMapper(), tempDirectory.toString());
        service.listShows("alex");
        SeriesShow created = service.createShow("alex", request("Orbit House"));

        assertThrows(IllegalArgumentException.class,
            () -> service.createShow("alex", request("Bad date", "10/31/2026")));
        assertThrows(IllegalArgumentException.class, () -> service.listShows("../outside"));
        assertThrows(java.util.NoSuchElementException.class,
            () -> service.updateEpisode("sam", created.id(), new EpisodeWatchRequest(1, 1, null, true, null)));
    }

    @Test
    void acceptsImageCoversAndDeletesThemWithTheirSeries() throws IOException {
        SeriesTrackerService service = new SeriesTrackerService(new ObjectMapper(), tempDirectory.toString());
        service.listShows("alex");
        SeriesShow created = service.createShow("alex", request("Orbit House"));
        ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", imageBytes);

        SeriesShow withCover = service.uploadCover("alex", created.id(),
            new MockMultipartFile("file", "cover.png", "image/png", imageBytes.toByteArray()));
        Path storedCover = service.coverPath(withCover.coverUrl().substring("/api/series/covers/".length()));

        assertTrue(Files.isRegularFile(storedCover));
        service.deleteShow("alex", created.id());
        assertFalse(Files.exists(storedCover));
    }

    @Test
    void rejectsAnImageWithMismatchedContentType() {
        SeriesTrackerService service = new SeriesTrackerService(new ObjectMapper(), tempDirectory.toString());
        service.listShows("alex");
        SeriesShow created = service.createShow("alex", request("Orbit House"));

        assertThrows(IllegalArgumentException.class, () -> service.uploadCover("alex", created.id(),
            new MockMultipartFile("file", "cover.jpg", "image/jpeg", new byte[]{1, 2, 3, 4})));
    }

    private SeriesShowRequest request(String title) {
        return request(title, "2026-10-10");
    }

    private SeriesShowRequest request(String title, String nextEpisodeDate) {
        return new SeriesShowRequest(
            title, "A found-family story.", "AIRING", "Streambox", List.of("Drama", "Fantasy"),
            "MONDAY", "20:30", "Europe/Madrid", 2, 12, nextEpisodeDate, 8.5,
            "2026-08-10", null, "Keep an eye on the moon."
        );
    }
}
