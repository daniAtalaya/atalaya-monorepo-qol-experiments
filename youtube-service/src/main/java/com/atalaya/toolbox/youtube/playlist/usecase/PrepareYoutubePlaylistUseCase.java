package com.atalaya.toolbox.youtube.playlist.usecase;

import com.atalaya.toolbox.youtube.playlist.domain.PreparedPlaylistPreparationResult;

public interface PrepareYoutubePlaylistUseCase {
    PreparedPlaylistPreparationResult prepare(String playlistUrl);
    PreparedPlaylistPreparationResult prepare(String playlistUrl, String destination);
}