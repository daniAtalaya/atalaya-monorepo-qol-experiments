package com.atalaya.toolbox.youtube.download.adapter.out.ytdlp;

import com.atalaya.toolbox.youtube.shared.YtDlpClient;
import com.jfposton.ytdlp.YtDlpRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YtDlpClientTest {

    @Test
    void createsFilenameFromTitleAndVideoId() {
        assertEquals("Song Title --- abc_123.mp3", YtDlpClient.filenameFor("Song Title", "abc_123"));
    }

    @Test
    void replacesFilesystemReservedCharactersInTitle() {
        assertEquals("Song _ Live_ --- abc_123.mp3", YtDlpClient.filenameFor("Song / Live?", "abc_123"));
    }

    @Test
    void usesFallbackWhenTitleHasNoFilenameCharacters() {
        assertEquals("youtube-track --- abc_123.mp3", YtDlpClient.filenameFor("...", "abc_123"));
    }

    @Test
    void preservesSpacesWithinProcessArguments() {
        YtDlpRequest request = new YtDlpRequest("https://www.youtube.com/watch?v=video_123");
        request.setOption("output", "folder with spaces/%(id)s.%(ext)s");
        request.setOption("ignore-errors");

        assertEquals(List.of(
            "https://www.youtube.com/watch?v=video_123",
            "--output", "folder with spaces/%(id)s.%(ext)s",
            "--ignore-errors"
        ), YtDlpClient.customBuildArguments(request));
    }
}
