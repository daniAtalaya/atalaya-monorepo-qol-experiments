package com.atalaya.toolbox.youtube.playlist.batch;

import com.atalaya.toolbox.youtube.playlist.domain.PreparedTrack;

public record PreparedPlaylistTrack(String playlistId, PreparedTrack track) {}