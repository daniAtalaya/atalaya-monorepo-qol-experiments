package com.atalaya.toolbox.youtube.download.domain;

import com.atalaya.toolbox.youtube.history.domain.DownloadHistoryEntry;

import java.util.List;

public record YoutubeDownloadResult(String requestedUrl, int downloadedCount, List<DownloadHistoryEntry> tracks) {}