package com.atalaya.toolbox.youtube.playlist.scheduler;

import java.util.Optional;

public interface PreparedPlaylistMetadataJobScheduler {
    boolean schedule(String playlistId, String playlistUrl, String destination);

    Optional<Status> status(String playlistId);

    record Status(String state, String error) {}
}
