package com.atalaya.toolbox.youtube.history.service;

import com.atalaya.toolbox.youtube.history.domain.DownloadHistoryEntry;
import com.atalaya.toolbox.youtube.history.repository.DownloadHistoryRepository;
import com.atalaya.toolbox.youtube.history.usecase.YoutubeHistoryUseCase;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

@Service
public class YoutubeHistoryService implements YoutubeHistoryUseCase {
    private final DownloadHistoryRepository historyRepository;

    public YoutubeHistoryService(DownloadHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    @Override
    public List<DownloadHistoryEntry> history() {
        return historyRepository.findAll();
    }

    @Override
    public List<DownloadHistoryEntry> history(String dateOrder, String downloadTimeOrder) {
        Comparator<DownloadHistoryEntry> comparator = null;
        if (dateOrder != null) {
            comparator = comparatorFor(DownloadHistoryEntry::downloadedAt, dateOrder);
        }
        if (downloadTimeOrder != null) {
            Comparator<DownloadHistoryEntry> durationComparator =
                comparatorFor(DownloadHistoryEntry::downloadTimeMillis, downloadTimeOrder);
            comparator = comparator == null ? durationComparator : comparator.thenComparing(durationComparator);
        }
        return comparator == null
            ? history()
            : historyRepository.findAll().stream().sorted(comparator).toList();
    }

    private <T extends Comparable<? super T>> Comparator<DownloadHistoryEntry> comparatorFor(
        Function<DownloadHistoryEntry, T> value, String order
    ) {
        boolean ascending;
        if ("asc".equalsIgnoreCase(order)) {
            ascending = true;
        } else if ("desc".equalsIgnoreCase(order)) {
            ascending = false;
        } else {
            throw new IllegalArgumentException("El orden debe ser 'asc' o 'desc'.");
        }
        Comparator<DownloadHistoryEntry> comparator = Comparator.comparing(value);
        return ascending ? comparator : comparator.reversed();
    }
}
