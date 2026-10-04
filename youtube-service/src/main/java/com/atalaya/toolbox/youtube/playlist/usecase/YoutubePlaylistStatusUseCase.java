package com.atalaya.toolbox.youtube.playlist.usecase;

import java.util.List;

public interface YoutubePlaylistStatusUseCase {
    PlaylistStatusReport status();
    record PlaylistStatusReport(int playlistCount, int completePlaylistCount, int incompletePlaylistCount, int totalTrackCount, int pendingTrackCount, List<PlaylistStatus> playlists) {}
    record PlaylistStatus(String playlistId, boolean complete, int totalTrackCount, int downloadedTrackCount, int pendingTrackCount) {}
}