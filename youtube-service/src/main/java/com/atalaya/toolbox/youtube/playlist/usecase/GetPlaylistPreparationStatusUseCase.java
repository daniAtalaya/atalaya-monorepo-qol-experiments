package com.atalaya.toolbox.youtube.playlist.usecase;

public interface GetPlaylistPreparationStatusUseCase {
    Status get(String playlistId);

    record Status(String playlistId, String status, String metadataFile, String error) {}
}
