package com.atalaya.toolbox.youtube.download.service;

import com.atalaya.toolbox.youtube.download.provider.YoutubeMediaDownloader;
import com.atalaya.toolbox.youtube.download.usecase.YoutubeDownloadUseCase;
import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import com.atalaya.toolbox.youtube.download.domain.DownloadedTrack;
import com.atalaya.toolbox.youtube.download.domain.YoutubeDownloadException;
import com.atalaya.toolbox.youtube.download.domain.YoutubeDownloadResult;
import com.atalaya.toolbox.youtube.history.repository.DownloadHistoryRepository;
import com.atalaya.toolbox.youtube.history.domain.DownloadHistoryEntry;
import com.atalaya.toolbox.youtube.settings.domain.YoutubeUrls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class YoutubeDownloadService implements YoutubeDownloadUseCase {
    private static final Logger LOGGER = LoggerFactory.getLogger(YoutubeDownloadService.class);

    private final YoutubeMediaDownloader mediaDownloader;
    private final DownloadHistoryRepository historyRepository;

    public YoutubeDownloadService(YoutubeMediaDownloader mediaDownloader, DownloadHistoryRepository historyRepository) {
        this.mediaDownloader = mediaDownloader;
        this.historyRepository = historyRepository;
    }

    @Override
    public YoutubeDownloadResult download(String url) {
        return download(url, null);
    }

    @Override
    public YoutubeDownloadResult download(String url, String destination) {
        String normalizedUrl = YoutubeUrls.normalizeVideoUrl(url);
        LOGGER.info("Received YouTube download request for {}", normalizedUrl);
        YoutubeProperties.validateDestination(destination);
        List<DownloadedTrack> downloadedTracks = mediaDownloader.download(normalizedUrl, destination);
        if (downloadedTracks.isEmpty()) {
            LOGGER.warn("No MP3 tracks were produced for {}", normalizedUrl);
            throw new YoutubeDownloadException("yt-dlp no produjo canciones MP3 descargadas.");
        }

        LOGGER.info("Downloaded {} track(s) from {}", downloadedTracks.size(), normalizedUrl);
        List<DownloadHistoryEntry> historyEntries = new ArrayList<>(downloadedTracks.size());
        for (DownloadedTrack track : downloadedTracks) {
            DownloadHistoryEntry entry = new DownloadHistoryEntry(
                track.videoId(), track.name(), track.url(), normalizedUrl, Instant.now().toString(),
                track.file(), track.downloadTimeMillis());
            historyRepository.save(entry);
            LOGGER.info("Recorded '{}' in download history", track.name());
            historyEntries.add(entry);
        }
        LOGGER.info("Finished YouTube download request for {}", normalizedUrl);
        return new YoutubeDownloadResult(normalizedUrl, historyEntries.size(), List.copyOf(historyEntries));
    }

}
