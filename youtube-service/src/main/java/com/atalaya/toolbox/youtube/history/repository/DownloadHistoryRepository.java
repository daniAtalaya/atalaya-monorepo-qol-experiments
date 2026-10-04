package com.atalaya.toolbox.youtube.history.repository;

import com.atalaya.toolbox.youtube.history.domain.DownloadHistoryEntry;

import java.util.List;

public interface DownloadHistoryRepository {
    void save(DownloadHistoryEntry entry);
    void replaceByVideoId(DownloadHistoryEntry entry);
    List<DownloadHistoryEntry> findAll();
}