package com.atalaya.toolbox.youtube.playlist.service;

import com.atalaya.toolbox.youtube.playlist.domain.PreparedPlaylist;
import com.atalaya.toolbox.youtube.playlist.repository.PreparedPlaylistRepository;
import com.atalaya.toolbox.youtube.playlist.scheduler.PreparedPlaylistJobScheduler;
import com.atalaya.toolbox.youtube.playlist.usecase.SchedulePreparedPlaylistProcessingUseCase;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SchedulePreparedPlaylistProcessingService implements SchedulePreparedPlaylistProcessingUseCase {
    private final PreparedPlaylistRepository playlistRepository;
    private final PreparedPlaylistJobScheduler jobScheduler;

    public SchedulePreparedPlaylistProcessingService(PreparedPlaylistRepository playlistRepository, PreparedPlaylistJobScheduler jobScheduler) {
        this.playlistRepository = playlistRepository;
        this.jobScheduler = jobScheduler;
    }

    @Override
    public List<String> schedule(String playlistId) {
        List<String> scheduledIds = playlistId != null && !playlistId.isBlank() ? List.of(playlistRepository.findById(playlistId).filter(playlist -> !playlist.tracks().isEmpty()).map(PreparedPlaylist::id).orElseThrow(() -> new IllegalArgumentException("No existe una playlist preparada con canciones pendientes."))) : playlistRepository.findPendingIds();
        scheduledIds.forEach(jobScheduler::schedule);
        return scheduledIds;
    }
}