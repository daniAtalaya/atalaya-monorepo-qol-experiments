package com.atalaya.toolbox.youtube.playlist.scheduler;

import com.atalaya.toolbox.youtube.playlist.usecase.PreparePlaylistMetadataUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.Optional;

@Component
public class SpringPreparedPlaylistMetadataJobScheduler implements PreparedPlaylistMetadataJobScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpringPreparedPlaylistMetadataJobScheduler.class);

    private final Set<String> activePlaylistIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Status> statuses = new ConcurrentHashMap<>();
    private final PreparePlaylistMetadataUseCase preparePlaylistMetadataUseCase;
    private final Executor executor;

    public SpringPreparedPlaylistMetadataJobScheduler(
        PreparePlaylistMetadataUseCase preparePlaylistMetadataUseCase,
        @Qualifier("youtubePlaylistJobExecutor") Executor executor
    ) {
        this.preparePlaylistMetadataUseCase = preparePlaylistMetadataUseCase;
        this.executor = executor;
    }

    @Override
    public boolean schedule(String playlistId, String playlistUrl, String destination) {
        if (!activePlaylistIds.add(playlistId)) {
            LOGGER.info("Metadata preparation for playlist {} is already queued or running", playlistId);
            return false;
        }
        statuses.put(playlistId, new Status("preparing", null));
        try {
            executor.execute(() -> prepareAsync(playlistId, playlistUrl, destination));
        } catch (RuntimeException e) {
            activePlaylistIds.remove(playlistId);
            statuses.put(playlistId, new Status("failed", e.getMessage()));
            throw e;
        }
        return true;
    }

    @Override
    public Optional<Status> status(String playlistId) {
        return Optional.ofNullable(statuses.get(playlistId));
    }

    private void prepareAsync(String playlistId, String playlistUrl, String destination) {
        try {
            LOGGER.info("Starting background metadata preparation for playlist {}", playlistId);
            preparePlaylistMetadataUseCase.prepare(playlistId, playlistUrl, destination);
            statuses.put(playlistId, new Status("prepared", null));
        } catch (RuntimeException e) {
            LOGGER.error("Background metadata preparation failed for playlist {}", playlistId, e);
            statuses.put(playlistId, new Status("failed", e.getMessage()));
        } finally {
            activePlaylistIds.remove(playlistId);
        }
    }
}
