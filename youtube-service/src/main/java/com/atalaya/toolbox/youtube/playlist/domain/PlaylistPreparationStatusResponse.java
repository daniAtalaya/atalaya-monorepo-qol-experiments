package com.atalaya.toolbox.youtube.playlist.domain;

public record PlaylistPreparationStatusResponse(String playlistId, String status, String metadataFile, String error) {}
