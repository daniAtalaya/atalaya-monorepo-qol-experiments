package com.atalaya.toolbox.youtube.history.domain;

public record DownloadHistoryEntry(String videoId, String name, String url, String requestedUrl, String downloadedAt, String file, long downloadTimeMillis) {}