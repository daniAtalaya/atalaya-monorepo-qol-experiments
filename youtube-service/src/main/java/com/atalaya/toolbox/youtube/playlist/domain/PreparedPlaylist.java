package com.atalaya.toolbox.youtube.playlist.domain;

import java.util.List;

public record PreparedPlaylist(String id, String requestedUrl, String preparedAt, int totalTrackCount, List<PreparedTrack> tracks, String destination) {}