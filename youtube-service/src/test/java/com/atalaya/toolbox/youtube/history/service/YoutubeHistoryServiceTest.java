package com.atalaya.toolbox.youtube.history.service;

import com.atalaya.toolbox.youtube.history.domain.DownloadHistoryEntry;
import com.atalaya.toolbox.youtube.history.repository.DownloadHistoryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YoutubeHistoryServiceTest {
    @Test
    void sortsByOptionalDurationAndDateOrders() {
        DownloadHistoryRepository history = mock(DownloadHistoryRepository.class);
        DownloadHistoryEntry olderLong = entry("older", "2026-09-28T10:00:00Z", 3000);
        DownloadHistoryEntry newerShort = entry("newer", "2026-09-29T10:00:00Z", 1000);
        DownloadHistoryEntry newerLong = entry("newer-long", "2026-09-29T10:00:00Z", 4000);
        when(history.findAll()).thenReturn(List.of(newerLong, newerShort, olderLong));

        YoutubeHistoryService service = new YoutubeHistoryService(history);

        assertEquals(List.of(olderLong, newerShort, newerLong), service.history("asc", "asc"));
        assertEquals(List.of(newerLong, olderLong, newerShort), service.history(null, "desc"));
    }

    @Test
    void rejectsUnsupportedHistorySortOrder() {
        YoutubeHistoryService service = new YoutubeHistoryService(mock(DownloadHistoryRepository.class));

        assertThrows(IllegalArgumentException.class, () -> service.history("sideways", null));
    }

    private DownloadHistoryEntry entry(String videoId, String downloadedAt, long duration) {
        return new DownloadHistoryEntry(
            videoId,
            videoId,
            "https://www.youtube.com/watch?v=" + videoId,
            "https://www.youtube.com/watch?v=source",
            downloadedAt,
            "music/" + videoId + ".mp3",
            duration
        );
    }
}
