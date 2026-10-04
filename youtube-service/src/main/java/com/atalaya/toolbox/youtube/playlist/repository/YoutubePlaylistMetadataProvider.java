package com.atalaya.toolbox.youtube.playlist.repository;

import com.atalaya.toolbox.youtube.playlist.domain.PreparedTrack;

import java.util.List;

public interface YoutubePlaylistMetadataProvider {
    List<PreparedTrack> fetchMetadata(String playlistUrl);
}