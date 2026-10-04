package com.atalaya.toolbox.youtube.playlist.service;

import com.atalaya.toolbox.youtube.playlist.repository.PreparedPlaylistRepository;
import com.atalaya.toolbox.youtube.playlist.scheduler.PreparedPlaylistJobScheduler;
import com.atalaya.toolbox.youtube.playlist.scheduler.PreparedPlaylistMetadataJobScheduler;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrepareYoutubePlaylistServiceTest {

    private static final String PLAYLIST_URL =
            "https://www.youtube.com/watch?v=video_123&list=PL_ab-123";

    @Test
    void stripsVideoParameterAndUsesYoutubePlaylistIdAsPreparedId() {
        PreparedPlaylistRepository repository = mock(PreparedPlaylistRepository.class);
        PreparedPlaylistJobScheduler scheduler = mock(PreparedPlaylistJobScheduler.class);
        PreparedPlaylistMetadataJobScheduler metadataScheduler = mock(PreparedPlaylistMetadataJobScheduler.class);
        when(repository.findById("PL_ab-123")).thenReturn(Optional.empty());
        when(repository.filePath("PL_ab-123")).thenReturn(Path.of("PL_ab-123.json"));
        PrepareYoutubePlaylistService service = new PrepareYoutubePlaylistService(
                repository, scheduler, metadataScheduler);

        var result = service.prepare(PLAYLIST_URL);

        assertEquals("PL_ab-123", result.playlistId());
        assertEquals("preparing", result.status());
        verify(metadataScheduler).schedule("PL_ab-123", "https://www.youtube.com/watch?list=PL_ab-123", null);
        verify(scheduler, never()).schedule("PL_ab-123");
    }

    @Test
    void rejectsVideoOnlyUrlBeforeFetchingPlaylistMetadata() {
        PrepareYoutubePlaylistService service = new PrepareYoutubePlaylistService(
                mock(PreparedPlaylistRepository.class),
                mock(PreparedPlaylistJobScheduler.class),
                mock(PreparedPlaylistMetadataJobScheduler.class));

        assertThrows(IllegalArgumentException.class,
                () -> service.prepare("https://www.youtube.com/watch?v=video_123"));
    }
}
