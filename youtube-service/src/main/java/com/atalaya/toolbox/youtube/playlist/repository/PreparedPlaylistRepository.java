package com.atalaya.toolbox.youtube.playlist.repository;

import com.atalaya.toolbox.youtube.playlist.domain.PreparedPlaylist;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface PreparedPlaylistRepository {
    Optional<PreparedPlaylist> findById(String id);
    List<PreparedPlaylist> findAll();
    List<String> findPendingIds();
    void save(PreparedPlaylist playlist);
    void removeTrack(String playlistId, String videoId);
    Path filePath(String playlistId);
}