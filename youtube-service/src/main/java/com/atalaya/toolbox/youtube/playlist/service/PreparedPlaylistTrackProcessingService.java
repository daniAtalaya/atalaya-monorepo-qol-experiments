package com.atalaya.toolbox.youtube.playlist.service;

import com.atalaya.toolbox.youtube.download.usecase.YoutubeDownloadUseCase;
import com.atalaya.toolbox.youtube.history.usecase.ReconcileMissingHistoryUseCase;
import com.atalaya.toolbox.youtube.history.repository.DownloadHistoryRepository;
import com.atalaya.toolbox.youtube.playlist.repository.PreparedPlaylistRepository;
import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import com.atalaya.toolbox.youtube.history.domain.DownloadHistoryEntry;
import com.atalaya.toolbox.youtube.playlist.domain.PreparedPlaylist;
import com.atalaya.toolbox.youtube.playlist.domain.PreparedTrack;
import com.atalaya.toolbox.youtube.download.domain.YoutubeDownloadException;
import com.atalaya.toolbox.youtube.download.domain.YoutubeDownloadResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

@Service
public class PreparedPlaylistTrackProcessingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PreparedPlaylistTrackProcessingService.class);

    private final YoutubeDownloadUseCase downloadUseCase;
    private final PreparedPlaylistRepository playlistRepository;
    private final DownloadHistoryRepository historyRepository;
    private final YoutubeProperties properties;

    public PreparedPlaylistTrackProcessingService(
        YoutubeDownloadUseCase downloadUseCase,
        PreparedPlaylistRepository playlistRepository,
        DownloadHistoryRepository historyRepository,
        YoutubeProperties properties
    ) {
        this.downloadUseCase = downloadUseCase;
        this.playlistRepository = playlistRepository;
        this.historyRepository = historyRepository;
        this.properties = properties;
    }

    public void processTrack(String playlistId, PreparedTrack track) {
        PreparedPlaylist playlist = playlistRepository.findById(playlistId).orElseThrow(() -> new IllegalArgumentException("No existe una playlist preparada con el identificador indicado."));
        LOGGER.info("Processing prepared track '{}' ({}) from playlist {}", track.name(), track.videoId(), playlist.id());
        String destination = track.destination() == null || track.destination().isBlank() ? playlist.destination() : track.destination();
        if (isAlreadyDownloaded(playlist, track.videoId(), destination)) {
            LOGGER.info("Track '{}' ({}) already exists in download history", track.name(), track.videoId());
            return;
        }
        YoutubeDownloadResult result = downloadUseCase.download(track.url(), destination);
        if (result.downloadedCount() == 0) {
            throw new YoutubeDownloadException("La descarga no produjo ningún archivo.");
        }
        if (ReconcileMissingHistoryUseCase.RECONCILIATION_URL.equals(playlist.requestedUrl())) {
            result.tracks().forEach(historyRepository::replaceByVideoId);
        }
    }

    private boolean isAlreadyDownloaded(PreparedPlaylist playlist, String videoId, String destination) {
        if (ReconcileMissingHistoryUseCase.RECONCILIATION_URL.equals(playlist.requestedUrl())) {
            return hasMusicTrack(properties.resolvedMusicDirectory(destination), videoId);
        }
        boolean existsInHistory = historyRepository.findAll().stream()
            .filter(entry -> Objects.equals(entry.videoId(), videoId))
            .map(DownloadHistoryEntry::file)
            .filter(file -> file != null && !file.isBlank())
            .map(Path::of)
            .anyMatch(file -> Files.isRegularFile(file) && file.toFile().length() > 0);
        if (existsInHistory) {
            return true;
        }
        Path musicDirectory = properties.resolvedMusicDirectory(null);
        if (Files.isDirectory(musicDirectory)) {
            try (var files = Files.walk(musicDirectory)) {
                boolean existsInMusicDirectory = files
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .anyMatch(filename -> filename.toLowerCase(java.util.Locale.ROOT).endsWith(".mp3") && (filename.substring(0, filename.length() - 4).equals(videoId) || filename.substring(0, filename.length() - 4).endsWith(" --- " + videoId)));
                if (existsInMusicDirectory) {
                    return true;
                }
            } catch (java.io.IOException e) {
                throw new YoutubeDownloadException("No se pudo revisar la carpeta de canciones MP3.", e);
            }
        }
        return false;
    }

    private boolean hasMusicTrack(Path musicDirectory, String videoId) {
        if (!Files.isDirectory(musicDirectory)) return false;
        try (var files = Files.list(musicDirectory)) {
            return files
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .anyMatch(filename -> filename.toLowerCase(Locale.ROOT).endsWith(".mp3") && (filename.substring(0, filename.length() - 4).equals(videoId) || filename.substring(0, filename.length() - 4).endsWith(" --- " + videoId)));
        } catch (java.io.IOException e) {
            throw new YoutubeDownloadException("No se pudo revisar la carpeta de canciones MP3.", e);
        }
    }
}