package com.atalaya.toolbox.youtube.playlist.service;

import com.atalaya.toolbox.youtube.playlist.domain.PreparedPlaylist;
import com.atalaya.toolbox.youtube.playlist.domain.PreparedPlaylistPreparationResult;
import com.atalaya.toolbox.youtube.playlist.repository.PreparedPlaylistRepository;
import com.atalaya.toolbox.youtube.playlist.scheduler.PreparedPlaylistJobScheduler;
import com.atalaya.toolbox.youtube.playlist.scheduler.PreparedPlaylistMetadataJobScheduler;
import com.atalaya.toolbox.youtube.playlist.usecase.PrepareYoutubePlaylistUseCase;
import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import com.atalaya.toolbox.youtube.settings.domain.YoutubeUrls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Objects;

@Service
public class PrepareYoutubePlaylistService implements PrepareYoutubePlaylistUseCase {
    private static final Logger LOGGER = LoggerFactory.getLogger(PrepareYoutubePlaylistService.class);
    private final PreparedPlaylistRepository playlistRepository;
    private final PreparedPlaylistJobScheduler jobScheduler;
    private final PreparedPlaylistMetadataJobScheduler metadataJobScheduler;

    public PrepareYoutubePlaylistService(
        PreparedPlaylistRepository playlistRepository,
        PreparedPlaylistJobScheduler jobScheduler,
        PreparedPlaylistMetadataJobScheduler metadataJobScheduler
    ) {
        this.playlistRepository = playlistRepository;
        this.jobScheduler = jobScheduler;
        this.metadataJobScheduler = metadataJobScheduler;
    }

    @Override
    public PreparedPlaylistPreparationResult prepare(String playlistUrl) {
        return prepare(playlistUrl, null);
    }

    @Override
    public PreparedPlaylistPreparationResult prepare(String playlistUrl, String destination) {
        String normalizedPlaylistUrl = YoutubeUrls.normalizePlaylistUrl(playlistUrl);
        YoutubeProperties.validateDestination(destination);
        String normalizedDestination = destination == null || destination.isBlank() ? null : Path.of(destination.strip()).normalize().toString();
        String playlistId = YoutubeUrls.playlistIdFromUrl(normalizedPlaylistUrl);
        PreparedPlaylist existing = playlistRepository.findById(playlistId)
            .filter(playlist -> !playlist.tracks().isEmpty())
            .orElse(null);
        if (existing != null) {
            if (!Objects.equals(existing.destination(), normalizedDestination)) {
                throw new IllegalArgumentException("La playlist ya tiene descargas pendientes en otro destino.");
            }
            jobScheduler.schedule(playlistId);
            return new PreparedPlaylistPreparationResult(playlistId, "processing", playlistRepository.filePath(playlistId).toString());
        }
        metadataJobScheduler.schedule(playlistId, normalizedPlaylistUrl, normalizedDestination);
        LOGGER.info("Accepted metadata preparation for playlist {}", playlistId);
        return new PreparedPlaylistPreparationResult(playlistId, "preparing", playlistRepository.filePath(playlistId).toString());
    }
}