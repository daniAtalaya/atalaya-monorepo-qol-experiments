package com.atalaya.toolbox.youtube.playlist.domain;

import java.util.Map;

public record PreparedTrack(String videoId, String name, String url, Map<String, Object> metadata, String destination) {
    public PreparedTrack(String videoId, String name, String url, Map<String, Object> metadata) {
        this(videoId, name, url, metadata, null);
    }
}