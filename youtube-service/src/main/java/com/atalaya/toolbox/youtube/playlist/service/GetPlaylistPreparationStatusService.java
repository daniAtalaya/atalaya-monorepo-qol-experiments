package com.atalaya.toolbox.youtube.playlist.service;

import com.atalaya.toolbox.youtube.playlist.domain.PreparedPlaylist;
import com.atalaya.toolbox.youtube.playlist.repository.PreparedPlaylistRepository;
import com.atalaya.toolbox.youtube.playlist.scheduler.PreparedPlaylistMetadataJobScheduler;
import com.atalaya.toolbox.youtube.playlist.usecase.GetPlaylistPreparationStatusUseCase;
import org.springframework.stereotype.Service;

@Service
public class GetPlaylistPreparationStatusService implements GetPlaylistPreparationStatusUseCase {
    private final PreparedPlaylistRepository playlistRepository;
    private final PreparedPlaylistMetadataJobScheduler metadataJobScheduler;

    public GetPlaylistPreparationStatusService(
        PreparedPlaylistRepository playlistRepository,
        PreparedPlaylistMetadataJobScheduler metadataJobScheduler
    ) {
        this.playlistRepository = playlistRepository;
        this.metadataJobScheduler = metadataJobScheduler;
    }

    @Override
    public Status get(String playlistId) {
        return metadataJobScheduler.status(playlistId)
            .map(status -> new Status(playlistId, status.state(), playlistRepository.filePath(playlistId).toString(), status.error()))
            .orElseGet(() -> playlistRepository.findById(playlistId)
                .map(this::preparedStatus)
                .orElseThrow(() -> new IllegalArgumentException("No existe una preparación para la playlist indicada.")));
    }

    private Status preparedStatus(PreparedPlaylist playlist) {
        String state = playlist.tracks().isEmpty() ? "complete" : "prepared";
        return new Status(playlist.id(), state, playlistRepository.filePath(playlist.id()).toString(), null);
    }
}
