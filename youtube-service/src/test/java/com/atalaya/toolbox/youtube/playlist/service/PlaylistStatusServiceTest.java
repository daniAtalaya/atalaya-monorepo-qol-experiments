package com.atalaya.toolbox.youtube.playlist.service;

import com.atalaya.toolbox.youtube.playlist.domain.PreparedPlaylist;
import com.atalaya.toolbox.youtube.playlist.domain.PreparedTrack;
import com.atalaya.toolbox.youtube.playlist.repository.PreparedPlaylistRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaylistStatusServiceTest {

    @Test
    void summarizesCompleteAndPendingPlaylistsQuantitatively() {
        PreparedPlaylistRepository repository = mock(PreparedPlaylistRepository.class);
        PreparedPlaylist complete = new PreparedPlaylist("complete", "url", "now", 2, List.of(), null);
        PreparedTrack pendingTrack = new PreparedTrack("video_123", "Song", "url", Map.of());
        PreparedPlaylist incomplete = new PreparedPlaylist(
                "incomplete", "url", "now", 3, List.of(pendingTrack), null);
        when(repository.findAll()).thenReturn(List.of(complete, incomplete));
        PlaylistStatusService service = new PlaylistStatusService(repository);

        var result = service.status();

        assertEquals(2, result.playlistCount());
        assertEquals(1, result.completePlaylistCount());
        assertEquals(1, result.incompletePlaylistCount());
        assertEquals(5, result.totalTrackCount());
        assertEquals(1, result.pendingTrackCount());
        assertEquals(2, result.playlists().get(0).downloadedTrackCount());
        assertEquals(2, result.playlists().get(1).downloadedTrackCount());
    }
}
