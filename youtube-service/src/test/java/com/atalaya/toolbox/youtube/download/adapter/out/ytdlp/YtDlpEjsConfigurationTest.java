package com.atalaya.toolbox.youtube.download.adapter.out.ytdlp;

import com.atalaya.toolbox.youtube.shared.YtDlpClient;
import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import com.jfposton.ytdlp.YtDlpRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YtDlpEjsConfigurationTest {

    @Test
    void configuresDenoAndOfficialEjsRemoteComponents() {
        YoutubeProperties properties = new YoutubeProperties(Path.of(".data", "youtube"));
        YtDlpRequest request = new YtDlpRequest("https://www.youtube.com/watch?v=video_123");

        YtDlpClient.configureEjs(request, properties);

        assertEquals("deno", request.getOption().get("js-runtimes"));
        assertEquals("ejs:github", request.getOption().get("remote-components"));
    }

    @Test
    void allowsOverridingTheRuntimeAndEjsSource() {
        YoutubeProperties properties = new YoutubeProperties(
                Path.of(".data", "youtube"), "node", "ejs:npm");
        YtDlpRequest request = new YtDlpRequest("https://www.youtube.com/watch?v=video_123");

        YtDlpClient.configureEjs(request, properties);

        assertEquals("node", request.getOption().get("js-runtimes"));
        assertEquals("ejs:npm", request.getOption().get("remote-components"));
    }
}
