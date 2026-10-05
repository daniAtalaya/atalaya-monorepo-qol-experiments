package com.atalaya.toolbox.youtube;

import com.atalaya.toolbox.youtube.playlist.repository.PreparedPlaylistRepository;
import com.atalaya.toolbox.youtube.history.usecase.GetInaccessibleVideoMetadataUseCase;
import com.atalaya.toolbox.youtube.download.service.YoutubeDownloadService;
import com.atalaya.toolbox.youtube.playlist.domain.PreparedPlaylist;
import com.atalaya.toolbox.youtube.playlist.domain.PreparedTrack;
import com.atalaya.toolbox.youtube.playlist.usecase.YoutubePlaylistStatusUseCase;
import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "toolbox.youtube.storage-directory=target",
    "toolbox.youtube.settings-file=target/test-youtube-settings.json",
    "toolbox.youtube.playlist-retry-delay-millis=0"
})
@AutoConfigureMockMvc
class YoutubeServiceApplicationTests {
    private static final String PLAYLIST_ID = "spring-batch-smoke-test";
    private String activePlaylistId = PLAYLIST_ID;

    @Autowired
    private PreparedPlaylistRepository playlistRepository;
    @Autowired
    private JobOperator jobOperator;
    @Autowired
    private Job preparedPlaylistDownloadJob;
    @Autowired
    private YoutubeProperties youtubeProperties;
    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private YoutubeDownloadService downloadUseCase;
    @MockitoBean
    private GetInaccessibleVideoMetadataUseCase inaccessibleVideoMetadataUseCase;
    @MockitoBean
    private YoutubePlaylistStatusUseCase playlistStatusUseCase;

    @Test
    void contextLoads() {
    }

    @Test
    void exposesPlaylistStatusFromTheFeatureController() throws Exception {
        when(playlistStatusUseCase.status()).thenReturn(
            new YoutubePlaylistStatusUseCase.PlaylistStatusReport(0, 0, 0, 0, 0, List.of()));

        mockMvc.perform(get("/api/youtube/playlist/status"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"playlistCount\":0")));
    }

    @Test
    void exposesItsMusicTreeAndPlayableMp3Files() throws Exception {
        Path musicDirectory = youtubeProperties.resolvedMusicDirectory(null);
        Path albumDirectory = Files.createDirectories(musicDirectory.resolve("library-test/album"));
        Path track = Files.writeString(albumDirectory.resolve("Library Track.mp3"), "test audio");
        Files.writeString(albumDirectory.resolve("ignored.txt"), "not music");

        mockMvc.perform(get("/api/youtube/music/tree"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Library Track.mp3")))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("ignored.txt"))));
        mockMvc.perform(get("/api/youtube/music/track").param("path", "library-test/album/Library Track.mp3"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("audio/mpeg"))
            .andExpect(content().bytes(Files.readAllBytes(track)));
        mockMvc.perform(get("/api/youtube/music/track").param("path", "../batch-metadata.mv.db"))
            .andExpect(status().isNotFound());
    }

    @Test
    void deletesOneInaccessibleMetadataEntryOrClearsTheRegistry() throws Exception {
        when(inaccessibleVideoMetadataUseCase.delete("unavailable-video")).thenReturn(true);
        when(inaccessibleVideoMetadataUseCase.delete("missing-video")).thenReturn(false);

        mockMvc.perform(delete("/api/youtube/history/metadata-inaccessible/unavailable-video"))
            .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/youtube/history/metadata-inaccessible/missing-video"))
            .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/youtube/history/metadata-inaccessible"))
            .andExpect(status().isNoContent());

        verify(inaccessibleVideoMetadataUseCase).delete("unavailable-video");
        verify(inaccessibleVideoMetadataUseCase).delete("missing-video");
        verify(inaccessibleVideoMetadataUseCase).clear();
    }

    @Test
    void startsAChunkJobAndPersistsSuccessfulTrackCompletion() throws Exception {
        activePlaylistId = PLAYLIST_ID;
        String videoId = "batch-video-test";
        Path musicDirectory = youtubeProperties.resolvedMusicDirectory(null);
        Files.createDirectories(musicDirectory);
        Files.createFile(musicDirectory.resolve("Batch Song --- " + videoId + ".mp3"));
        PreparedTrack track = new PreparedTrack(
            videoId,
            "Batch Song",
            "https://www.youtube.com/watch?v=" + videoId,
            Map.of("id", videoId)
        );
        playlistRepository.save(new PreparedPlaylist(
            PLAYLIST_ID,
            "https://www.youtube.com/playlist?list=" + PLAYLIST_ID,
            "2026-09-30T00:00:00Z",
            1,
            List.of(track),
            null
        ));
        JobParameters parameters = new JobParametersBuilder()
            .addString("playlistId", PLAYLIST_ID)
            .addLong("requestedAt", System.nanoTime())
            .toJobParameters();

        var execution = jobOperator.start(preparedPlaylistDownloadJob, parameters);

        assertEquals("COMPLETED", execution.getStatus().name());
        assertEquals(List.of(), playlistRepository.findById(PLAYLIST_ID).orElseThrow().tracks());
    }

    @Test
    void retriesFailedTracksAndLeavesThemPreparedForAnotherJob() throws Exception {
        String videoId = "batch-video-failure";
        activePlaylistId = PLAYLIST_ID + "-failure";
        PreparedTrack track = new PreparedTrack(
            videoId,
            "Failing Song",
            "https://www.youtube.com/watch?v=" + videoId,
            Map.of("id", videoId)
        );
        playlistRepository.save(new PreparedPlaylist(
            activePlaylistId,
            "https://www.youtube.com/playlist?list=" + activePlaylistId,
            "2026-09-30T00:00:00Z",
            1,
            List.of(track),
            null
        ));
        doThrow(new IllegalStateException("download unavailable"))
            .when(downloadUseCase).download(track.url(), null);

        var execution = jobOperator.start(preparedPlaylistDownloadJob, new JobParametersBuilder()
            .addString("playlistId", activePlaylistId)
            .addLong("requestedAt", System.nanoTime())
            .toJobParameters());

        assertEquals("COMPLETED", execution.getStatus().name());
        assertEquals(List.of(track), playlistRepository.findById(activePlaylistId).orElseThrow().tracks());
        verify(downloadUseCase, times(3)).download(track.url(), null);
    }

    @AfterEach
    void removeSmokeTestPlaylist() throws Exception {
        var playlistFile = playlistRepository.filePath(activePlaylistId);
        Files.deleteIfExists(playlistFile);
        Files.deleteIfExists(playlistFile.resolveSibling(activePlaylistId + ".lock"));
        Files.deleteIfExists(youtubeProperties.resolvedMusicDirectory(null)
            .resolve("Batch Song --- batch-video-test.mp3"));
        Path musicDirectory = youtubeProperties.resolvedMusicDirectory(null);
        Files.deleteIfExists(musicDirectory.resolve("library-test/album/Library Track.mp3"));
        Files.deleteIfExists(musicDirectory.resolve("library-test/album/ignored.txt"));
        Files.deleteIfExists(musicDirectory.resolve("library-test/album"));
        Files.deleteIfExists(musicDirectory.resolve("library-test"));
    }
}
