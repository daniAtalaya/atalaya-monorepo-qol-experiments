package com.atalaya.toolbox.youtube.download.service;

import com.atalaya.toolbox.youtube.download.provider.YoutubeMediaDownloader;
import com.atalaya.toolbox.youtube.history.repository.DownloadHistoryRepository;
import com.atalaya.toolbox.youtube.history.domain.DownloadHistoryEntry;
import com.atalaya.toolbox.youtube.download.domain.DownloadedTrack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YoutubeDownloadServiceTest {

    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=video_123";

    @Test
    void downloadsTracksAndRecordsHistory() {
        YoutubeMediaDownloader downloader = mock(YoutubeMediaDownloader.class);
        DownloadHistoryRepository history = mock(DownloadHistoryRepository.class);
        when(downloader.download(VIDEO_URL, null)).thenReturn(List.of(
                new DownloadedTrack("video_123", "Canción de prueba", VIDEO_URL, "music/video_123.mp3", 1250)));
        YoutubeDownloadService service = new YoutubeDownloadService(downloader, history);

        var result = service.download(VIDEO_URL);

        assertEquals(1, result.downloadedCount());
        assertEquals("Canción de prueba", result.tracks().getFirst().name());
        assertEquals(VIDEO_URL, result.tracks().getFirst().requestedUrl());
        assertEquals(1250, result.tracks().getFirst().downloadTimeMillis());
        verify(history).save(any(DownloadHistoryEntry.class));
    }

    @Test
    void rejectsNonYoutubeUrlsBeforeInvokingDownloader() {
        YoutubeMediaDownloader downloader = mock(YoutubeMediaDownloader.class);
        YoutubeDownloadService service = new YoutubeDownloadService(downloader, mock(DownloadHistoryRepository.class));

        assertThrows(IllegalArgumentException.class,
                () -> service.download("https://youtube.com.example.org/watch?v=video_123"));

        verify(downloader, never()).download(anyString(), any());
    }

    @Test
    void stripsPlaylistParameterWhenDownloadingVideoFromPlaylistUrl() {
        String combinedUrl = VIDEO_URL + "&list=playlist_123";
        YoutubeMediaDownloader downloader = mock(YoutubeMediaDownloader.class);
        DownloadHistoryRepository history = mock(DownloadHistoryRepository.class);
        when(downloader.download(VIDEO_URL, null)).thenReturn(List.of(
                new DownloadedTrack("video_123", "Song", VIDEO_URL, "music/song.mp3", 1250)));
        YoutubeDownloadService service = new YoutubeDownloadService(downloader, history);

        service.download(combinedUrl);

        verify(downloader).download(VIDEO_URL, null);
    }

    @Test
    void rejectsPlaylistOnlyUrlBeforeStartingIndividualDownload() {
        YoutubeMediaDownloader downloader = mock(YoutubeMediaDownloader.class);
        YoutubeDownloadService service = new YoutubeDownloadService(downloader, mock(DownloadHistoryRepository.class));

        assertThrows(IllegalArgumentException.class,
                () -> service.download("https://www.youtube.com/playlist?list=playlist_123"));

        verify(downloader, never()).download(anyString(), any());
    }
}
