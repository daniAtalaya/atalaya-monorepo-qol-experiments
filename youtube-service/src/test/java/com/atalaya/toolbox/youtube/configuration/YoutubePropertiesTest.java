package com.atalaya.toolbox.youtube.configuration;

import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YoutubePropertiesTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesRelativeDestinationsWithinMusicDirectory() {
        YoutubeProperties properties = new YoutubeProperties(temporaryDirectory);

        assertEquals(
                temporaryDirectory.resolve("music/pokemon/rejuvenation"),
                properties.resolvedMusicDirectory("pokemon/rejuvenation"));
        assertEquals(temporaryDirectory.resolve("music"), properties.resolvedMusicDirectory(null));
    }

    @Test
    void rejectsAbsoluteAndParentDirectoryDestinations() {
        YoutubeProperties properties = new YoutubeProperties(temporaryDirectory);

        assertThrows(IllegalArgumentException.class,
                () -> properties.resolvedMusicDirectory("../outside"));
        assertThrows(IllegalArgumentException.class,
                () -> properties.resolvedMusicDirectory(temporaryDirectory.toString()));
    }
}
