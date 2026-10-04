package com.atalaya.toolbox.youtube.history.service;

import com.atalaya.toolbox.youtube.history.repository.DownloadHistoryRepository;
import com.atalaya.toolbox.youtube.history.usecase.ReconcileMissingHistoryUseCase;
import com.atalaya.toolbox.youtube.playlist.scheduler.PreparedPlaylistJobScheduler;
import com.atalaya.toolbox.youtube.playlist.repository.PreparedPlaylistRepository;
import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import com.atalaya.toolbox.youtube.history.domain.DownloadHistoryEntry;
import com.atalaya.toolbox.youtube.playlist.domain.PreparedPlaylist;
import com.atalaya.toolbox.youtube.playlist.domain.PreparedTrack;
import com.atalaya.toolbox.youtube.download.domain.YoutubeDownloadException;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReconcileMissingHistoryService implements ReconcileMissingHistoryUseCase {
    private static final String VIDEO_ID_REGEX = "[A-Za-z0-9_-]+";

    private final DownloadHistoryRepository historyRepository;
    private final PreparedPlaylistRepository playlistRepository;
    private final PreparedPlaylistJobScheduler jobScheduler;
    private final YoutubeProperties properties;

    public ReconcileMissingHistoryService(DownloadHistoryRepository historyRepository, PreparedPlaylistRepository playlistRepository, PreparedPlaylistJobScheduler jobScheduler, YoutubeProperties properties) {
        this.historyRepository = historyRepository;
        this.playlistRepository = playlistRepository;
        this.jobScheduler = jobScheduler;
        this.properties = properties;
    }

    @Override
    public MissingHistoryReconciliationResult reconcile() {
        List<DownloadHistoryEntry> history = historyRepository.findAll();
        Map<String, DownloadHistoryEntry> entriesByVideoId = new LinkedHashMap<>();
        for (DownloadHistoryEntry entry : history) {
            if (entry.videoId() != null && entry.videoId().matches(VIDEO_ID_REGEX)) {
                entriesByVideoId.putIfAbsent(entry.videoId(), entry);
            }
        }
        List<PreparedTrack> missingTracks = entriesByVideoId.values().stream()
            .filter(entry -> !isHistoryFilePresent(entry))
            .map(this::toPreparedTrack)
            .toList();
        PreparedPlaylist existing = playlistRepository.findById(RECONCILIATION_URL).orElse(null);
        Map<String, PreparedTrack> pendingTracks = new LinkedHashMap<>();
        if (existing != null) {
            existing.tracks().forEach(track -> pendingTracks.put(track.videoId(), track));
        }
        int newlyQueuedCount = 0;
        for (PreparedTrack track : missingTracks) {
            if (!pendingTracks.containsKey(track.videoId())) {
                newlyQueuedCount++;
            }
            pendingTracks.put(track.videoId(), track);
        }
        List<PreparedTrack> queuedTracks = List.copyOf(pendingTracks.values());
        if (!queuedTracks.isEmpty()) {
            int totalTrackCount = (existing == null ? 0 : existing.totalTrackCount()) + newlyQueuedCount;
            PreparedPlaylist playlist = new PreparedPlaylist(RECONCILIATION_URL, RECONCILIATION_URL, existing == null ? java.time.Instant.now().toString() : existing.preparedAt(), Math.max(totalTrackCount, queuedTracks.size()), queuedTracks, null);
            playlistRepository.save(playlist);
            jobScheduler.schedule(RECONCILIATION_URL);
        }
        return new MissingHistoryReconciliationResult(entriesByVideoId.size(), entriesByVideoId.size() - missingTracks.size(), missingTracks.size(), queuedTracks.size(), queuedTracks.isEmpty() ? null : RECONCILIATION_URL);
    }

    private boolean isHistoryFilePresent(DownloadHistoryEntry entry) {
        Path expectedFile = historyFile(entry);
        return expectedFile != null && Files.isRegularFile(expectedFile)
            && expectedFile.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".mp3");
    }

    private PreparedTrack toPreparedTrack(DownloadHistoryEntry entry) {
        String url = entry.url() == null || entry.url().isBlank() ? "https://www.youtube.com/watch?v=" + entry.videoId() : entry.url();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("id", entry.videoId());
        metadata.put("title", entry.name() == null ? entry.videoId() : entry.name());
        metadata.put("webpage_url", url);
        Path expectedFile = historyFile(entry);
        String destination = expectedFile == null
            ? null
            : properties.resolvedMusicDirectory(null).toAbsolutePath().normalize()
                .relativize(expectedFile.getParent())
                .toString();
        return new PreparedTrack(
            entry.videoId(),
            entry.name() == null || entry.name().isBlank() ? entry.videoId() : entry.name(),
            url,
            metadata,
            destination == null || destination.isBlank() ? null : destination
        );
    }

    private Path historyFile(DownloadHistoryEntry entry) {
        if (entry.file() == null || entry.file().isBlank()) {
            return null;
        }
        Path musicDirectory = properties.resolvedMusicDirectory(null).toAbsolutePath().normalize();
        try {
            Path storedFile = Path.of(entry.file());
            Path expectedFile = (storedFile.isAbsolute() ? storedFile : musicDirectory.resolve(storedFile))
                .toAbsolutePath()
                .normalize();
            if (!expectedFile.startsWith(musicDirectory)) {
                throw new YoutubeDownloadException(
                    "La ruta guardada en el historial está fuera de la biblioteca: " + entry.file());
            }
            return expectedFile;
        } catch (RuntimeException e) {
            if (e instanceof YoutubeDownloadException downloadException) {
                throw downloadException;
            }
            throw new YoutubeDownloadException("La ruta guardada en el historial no es válida: " + entry.file(), e);
        }
    }
}