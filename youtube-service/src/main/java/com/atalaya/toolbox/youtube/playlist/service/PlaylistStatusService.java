package com.atalaya.toolbox.youtube.playlist.service;

import com.atalaya.toolbox.youtube.playlist.domain.PreparedPlaylist;
import com.atalaya.toolbox.youtube.playlist.repository.PreparedPlaylistRepository;
import com.atalaya.toolbox.youtube.playlist.usecase.YoutubePlaylistStatusUseCase;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PlaylistStatusService implements YoutubePlaylistStatusUseCase {
    private final PreparedPlaylistRepository playlistRepository;

    public PlaylistStatusService(PreparedPlaylistRepository playlistRepository) {
        this.playlistRepository = playlistRepository;
    }

    @Override
    public PlaylistStatusReport status() {
        List<PlaylistStatus> playlists = playlistRepository.findAll().stream().map(this::statusOf).toList();
        int completeCount = (int) playlists.stream().filter(PlaylistStatus::complete).count();
        return new PlaylistStatusReport(
            playlists.size(),
            completeCount,
            playlists.size() - completeCount,
            playlists.stream().mapToInt(PlaylistStatus::totalTrackCount).sum(),
            playlists.stream().mapToInt(PlaylistStatus::pendingTrackCount).sum(), playlists
        );
    }

    private PlaylistStatus statusOf(PreparedPlaylist playlist) {
        int pendingCount = playlist.tracks().size();
        int totalCount = Math.max(playlist.totalTrackCount(), pendingCount);
        return new PlaylistStatus(playlist.id(), pendingCount == 0, totalCount, Math.max(0, totalCount - pendingCount), pendingCount);
    }
}