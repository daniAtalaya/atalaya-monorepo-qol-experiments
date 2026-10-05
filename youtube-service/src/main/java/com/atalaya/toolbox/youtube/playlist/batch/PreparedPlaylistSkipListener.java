package com.atalaya.toolbox.youtube.playlist.batch;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.stereotype.Component;

@Component
public class PreparedPlaylistSkipListener implements SkipListener<PreparedPlaylistTrack, PreparedPlaylistTrackResult> {
    private static final Logger LOGGER = LoggerFactory.getLogger(PreparedPlaylistSkipListener.class);

    @Override
    public void onSkipInProcess(PreparedPlaylistTrack item, @NonNull Throwable error) {
        LOGGER.error(
            "Prepared track '{}' ({}) failed after Spring Batch retries; it remains in playlist {}",
            item.track().name(), item.track().videoId(), item.playlistId(), error
        );
    }

    @Override
    public void onSkipInWrite(PreparedPlaylistTrackResult item, @NonNull Throwable error) {
        LOGGER.error(
            "Could not persist completion of prepared track '{}' ({}); it remains eligible for retry in playlist {}",
            item.item().track().name(), item.item().track().videoId(), item.item().playlistId(), error
        );
    }
}