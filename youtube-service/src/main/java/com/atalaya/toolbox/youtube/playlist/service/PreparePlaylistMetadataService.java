package com.atalaya.toolbox.youtube.playlist.service;

import com.atalaya.toolbox.youtube.playlist.domain.PreparedPlaylist;
import com.atalaya.toolbox.youtube.playlist.domain.PreparedTrack;
import com.atalaya.toolbox.youtube.download.domain.YoutubeDownloadException;
import com.atalaya.toolbox.youtube.playlist.repository.PreparedPlaylistRepository;
import com.atalaya.toolbox.youtube.playlist.repository.YoutubePlaylistMetadataProvider;
import com.atalaya.toolbox.youtube.playlist.scheduler.PreparedPlaylistJobScheduler;
import com.atalaya.toolbox.youtube.playlist.usecase.PreparePlaylistMetadataUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class PreparePlaylistMetadataService implements PreparePlaylistMetadataUseCase {
    private static final Logger LOGGER = LoggerFactory.getLogger(PreparePlaylistMetadataService.class);

    private final YoutubePlaylistMetadataProvider metadataProvider;
    private final PreparedPlaylistRepository playlistRepository;
    private final PreparedPlaylistJobScheduler jobScheduler;

    public PreparePlaylistMetadataService(
        YoutubePlaylistMetadataProvider metadataProvider,
        PreparedPlaylistRepository playlistRepository,
        PreparedPlaylistJobScheduler jobScheduler
    ) {
        this.metadataProvider = metadataProvider;
        this.playlistRepository = playlistRepository;
        this.jobScheduler = jobScheduler;
    }

    @Override
    public void prepare(String playlistId, String playlistUrl, String destination) {
        List<PreparedTrack> tracks = metadataProvider.fetchMetadata(playlistUrl);
        if (tracks.isEmpty()) {
            throw new YoutubeDownloadException("No se encontraron canciones disponibles en la playlist.");
        }
        PreparedPlaylist playlist = new PreparedPlaylist(
            playlistId, playlistUrl, Instant.now().toString(), tracks.size(), List.copyOf(tracks), destination);
        playlistRepository.save(playlist);
        LOGGER.info("Prepared playlist {} with {} track(s)", playlistId, playlist.tracks().size());
        jobScheduler.schedule(playlistId);
    }
}
