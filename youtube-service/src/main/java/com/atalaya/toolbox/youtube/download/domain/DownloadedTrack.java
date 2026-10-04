package com.atalaya.toolbox.youtube.download.domain;

public record DownloadedTrack(String videoId, String name, String url, String file, long downloadTimeMillis) {}