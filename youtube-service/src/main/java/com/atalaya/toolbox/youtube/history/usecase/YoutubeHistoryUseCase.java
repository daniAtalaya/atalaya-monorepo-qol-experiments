package com.atalaya.toolbox.youtube.history.usecase;

import com.atalaya.toolbox.youtube.history.domain.DownloadHistoryEntry;

import java.util.List;

public interface YoutubeHistoryUseCase {
    List<DownloadHistoryEntry> history();
    List<DownloadHistoryEntry> history(String dateOrder, String downloadTimeOrder);
}