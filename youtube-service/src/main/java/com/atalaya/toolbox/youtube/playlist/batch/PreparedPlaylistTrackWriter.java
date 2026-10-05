package com.atalaya.toolbox.youtube.playlist.batch;

import com.atalaya.toolbox.youtube.playlist.repository.PreparedPlaylistRepository;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

@Component
public class PreparedPlaylistTrackWriter implements ItemWriter<PreparedPlaylistTrackResult> {
    private final PreparedPlaylistRepository playlistRepository;

    public PreparedPlaylistTrackWriter(PreparedPlaylistRepository playlistRepository) {
        this.playlistRepository = playlistRepository;
    }

    @Override
    public void write(Chunk<? extends PreparedPlaylistTrackResult> chunk) {
        for (PreparedPlaylistTrackResult result : chunk) {
            PreparedPlaylistTrack item = result.item();
            playlistRepository.removeTrack(item.playlistId(), item.track().videoId());
        }
    }
}