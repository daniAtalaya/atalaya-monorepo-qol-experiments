package com.atalaya.toolbox.youtube.playlist.service;

import com.atalaya.toolbox.youtube.download.usecase.YoutubeDownloadUseCase;
import com.atalaya.toolbox.youtube.history.usecase.ReconcileMissingHistoryUseCase;
import com.atalaya.toolbox.youtube.history.repository.DownloadHistoryRepository;
import com.atalaya.toolbox.youtube.playlist.repository.PreparedPlaylistRepository;
import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import com.atalaya.toolbox.youtube.history.domain.DownloadHistoryEntry;
import com.atalaya.toolbox.youtube.playlist.domain.PreparedPlaylist;
import com.atalaya.toolbox.youtube.playlist.domain.PreparedTrack;
import com.atalaya.toolbox.youtube.download.domain.YoutubeDownloadResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PreparedPlaylistTrackProcessingServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void skipsAValidHistoryFileForANormalPlaylist() throws Exception {
        Path archivedFile = temporaryDirectory.resolve("archived.mp3");
        Files.writeString(archivedFile, "audio");
        PreparedTrack track = track("archived_123");
        PreparedPlaylist playlist = playlist("playlist_123", track);
        YoutubeDownloadUseCase downloader = mock(YoutubeDownloadUseCase.class);
        PreparedPlaylistRepository playlistRepository = mock(PreparedPlaylistRepository.class);
        DownloadHistoryRepository historyRepository = mock(DownloadHistoryRepository.class);
        when(playlistRepository.findById(playlist.id())).thenReturn(Optional.of(playlist));
        when(historyRepository.findAll()).thenReturn(List.of(new DownloadHistoryEntry(
            track.videoId(), track.name(), track.url(), playlist.id(),
            "2026-09-29T10:00:00Z", archivedFile.toString(), 1000)));
        PreparedPlaylistTrackProcessingService service = new PreparedPlaylistTrackProcessingService(
            downloader, playlistRepository, historyRepository, new YoutubeProperties(temporaryDirectory));

        service.processTrack(playlist.id(), track);

        verifyNoInteractions(downloader);
    }

    @Test
    void reconciliationPlaylistRedownloadsWhenItsHistoryFileIsOutsideTheMusicDirectory() throws Exception {
        PreparedTrack track = track("missing_123");
        Path archivedFile = Files.createFile(temporaryDirectory.resolve("archived.mp3"));
        PreparedPlaylist playlist = playlist(ReconcileMissingHistoryUseCase.RECONCILIATION_URL, track);
        YoutubeDownloadUseCase downloader = mock(YoutubeDownloadUseCase.class);
        PreparedPlaylistRepository playlistRepository = mock(PreparedPlaylistRepository.class);
        DownloadHistoryRepository historyRepository = mock(DownloadHistoryRepository.class);
        when(playlistRepository.findById(playlist.id())).thenReturn(Optional.of(playlist));
        when(historyRepository.findAll()).thenReturn(List.of(new DownloadHistoryEntry(
            track.videoId(), track.name(), track.url(), "old-playlist",
            "2026-09-29T10:00:00Z", archivedFile.toString(), 1000)));
        DownloadHistoryEntry reconciledEntry = new DownloadHistoryEntry(
            track.videoId(), track.name(), track.url(), ReconcileMissingHistoryUseCase.RECONCILIATION_URL,
            "2026-09-30T12:00:00Z", temporaryDirectory.resolve("music/" + track.videoId() + ".mp3").toString(), 1000);
        when(downloader.download(track.url(), null))
            .thenReturn(new YoutubeDownloadResult(track.url(), 1, List.of(reconciledEntry)));
        PreparedPlaylistTrackProcessingService service = new PreparedPlaylistTrackProcessingService(
            downloader, playlistRepository, historyRepository, new YoutubeProperties(temporaryDirectory));

        service.processTrack(playlist.id(), track);

        verify(downloader).download(track.url(), null);
        verify(historyRepository).replaceByVideoId(reconciledEntry);
    }

    @Test
    void findsMusicTracksRecursivelyBeforeRequeueingThem() throws Exception {
        Path nestedMusicDirectory = Files.createDirectories(
            temporaryDirectory.resolve("music/pokemon/rejuvenation"));
        Files.createFile(nestedMusicDirectory.resolve("Nested Song --- nested_123.mp3"));
        PreparedTrack baseTrack = track("nested_123");
        PreparedTrack track = new PreparedTrack(
            baseTrack.videoId(), baseTrack.name(), baseTrack.url(), baseTrack.metadata(), "pokemon/rejuvenation");
        PreparedPlaylist playlist = playlist(ReconcileMissingHistoryUseCase.RECONCILIATION_URL, track);
        YoutubeDownloadUseCase downloader = mock(YoutubeDownloadUseCase.class);
        PreparedPlaylistRepository playlistRepository = mock(PreparedPlaylistRepository.class);
        DownloadHistoryRepository historyRepository = mock(DownloadHistoryRepository.class);
        when(playlistRepository.findById(playlist.id())).thenReturn(Optional.of(playlist));
        PreparedPlaylistTrackProcessingService service = new PreparedPlaylistTrackProcessingService(
            downloader, playlistRepository, historyRepository, new YoutubeProperties(temporaryDirectory));

        service.processTrack(playlist.id(), track);

        verifyNoInteractions(downloader);
    }

    @Test
    void reconciliationRedownloadsIntoTheRecordedFolderEvenIfAnotherCopyExistsAtRoot() throws Exception {
        String destination = "comercial/badbunny/epicas";
        Path musicDirectory = Files.createDirectories(temporaryDirectory.resolve("music"));
        Files.createFile(musicDirectory.resolve("Bad Bunny Song --- missing_456.mp3"));
        PreparedTrack track = new PreparedTrack(
            "missing_456",
            "Bad Bunny Song",
            "https://www.youtube.com/watch?v=missing_456",
            Map.of("id", "missing_456"),
            destination);
        PreparedPlaylist playlist = playlist(ReconcileMissingHistoryUseCase.RECONCILIATION_URL, track);
        YoutubeDownloadUseCase downloader = mock(YoutubeDownloadUseCase.class);
        PreparedPlaylistRepository playlistRepository = mock(PreparedPlaylistRepository.class);
        DownloadHistoryRepository historyRepository = mock(DownloadHistoryRepository.class);
        when(playlistRepository.findById(playlist.id())).thenReturn(Optional.of(playlist));
        DownloadHistoryEntry reconciledEntry = new DownloadHistoryEntry(
            track.videoId(), track.name(), track.url(), ReconcileMissingHistoryUseCase.RECONCILIATION_URL,
            "2026-09-30T12:00:00Z",
            temporaryDirectory.resolve("music").resolve(destination).resolve(track.videoId() + ".mp3").toString(), 1000);
        when(downloader.download(track.url(), destination))
            .thenReturn(new YoutubeDownloadResult(track.url(), 1, List.of(reconciledEntry)));
        PreparedPlaylistTrackProcessingService service = new PreparedPlaylistTrackProcessingService(
            downloader, playlistRepository, historyRepository, new YoutubeProperties(temporaryDirectory));

        service.processTrack(playlist.id(), track);

        verify(downloader).download(track.url(), destination);
        verify(historyRepository).replaceByVideoId(reconciledEntry);
    }

    private PreparedPlaylist playlist(String id, PreparedTrack track) {
        return new PreparedPlaylist(
            id,
            id.equals(ReconcileMissingHistoryUseCase.RECONCILIATION_URL)
                ? id
                : "https://www.youtube.com/playlist?list=" + id,
            "2026-09-29T10:00:00Z",
            1,
            List.of(track),
            null
        );
    }

    private PreparedTrack track(String videoId) {
        return new PreparedTrack(
            videoId,
            "Song " + videoId,
            "https://www.youtube.com/watch?v=" + videoId,
            Map.of("id", videoId, "title", "Song " + videoId));
    }
}
