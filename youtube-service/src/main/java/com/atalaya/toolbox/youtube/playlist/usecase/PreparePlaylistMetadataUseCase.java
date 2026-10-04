package com.atalaya.toolbox.youtube.playlist.usecase;

public interface PreparePlaylistMetadataUseCase {
    void prepare(String playlistId, String playlistUrl, String destination);
}
