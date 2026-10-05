package com.atalaya.toolbox.youtube.playlist.batch;

import com.atalaya.toolbox.youtube.playlist.service.PreparedPlaylistTrackProcessingService;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

@Component
public class PreparedPlaylistTrackProcessor implements ItemProcessor<PreparedPlaylistTrack, PreparedPlaylistTrackResult> {
    private final PreparedPlaylistTrackProcessingService trackProcessingService;

    public PreparedPlaylistTrackProcessor(PreparedPlaylistTrackProcessingService trackProcessingService) {
        this.trackProcessingService = trackProcessingService;
    }

    @Override
    public PreparedPlaylistTrackResult process(PreparedPlaylistTrack item) {
        trackProcessingService.processTrack(item.playlistId(), item.track());
        return new PreparedPlaylistTrackResult(item);
    }
}