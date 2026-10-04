package com.atalaya.toolbox.youtube.library.adapter.out.filesystem;

import com.atalaya.toolbox.youtube.library.adapter.FilesystemMusicLibraryAdapter;
import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemMusicLibraryAdapterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void listsTheWholeRecursiveTreeAndSupportsLazyFolderDepths() throws Exception {
        Path musicDirectory = Files.createDirectories(temporaryDirectory.resolve("music"));
        Files.createDirectories(musicDirectory.resolve("artist/album/disc"));
        Files.writeString(musicDirectory.resolve("root.mp3"), "root");
        Files.writeString(musicDirectory.resolve("artist/artist.mp3"), "artist");
        Files.writeString(musicDirectory.resolve("artist/album/album.mp3"), "album");
        Files.writeString(musicDirectory.resolve("artist/album/disc/disc.mp3"), "disc");
        Files.writeString(musicDirectory.resolve("artist/album/cover.jpg"), "image");
        FilesystemMusicLibraryAdapter adapter = new FilesystemMusicLibraryAdapter(
            new YoutubeProperties(temporaryDirectory));

        var fullTree = adapter.findTree("", null);
        var firstFolderLevel = adapter.findTree("", 0);
        var albumContents = adapter.findTree("artist/album", 0);
        var oneAlbumLevel = adapter.findTree("artist", 1);
        var artistInFullTree = fullTree.stream()
            .filter(node -> node.directory() && node.name().equals("artist"))
            .findFirst()
            .orElseThrow();

        assertEquals(2, fullTree.size());
        assertEquals(2, firstFolderLevel.size());
        assertTrue(firstFolderLevel.stream()
            .filter(node -> node.directory() && node.name().equals("artist"))
            .findFirst()
            .orElseThrow()
            .children()
            .isEmpty());
        assertEquals("album.mp3", albumContents.getFirst().name());
        var album = oneAlbumLevel.stream().filter(node -> node.directory() && node.name().equals("album"))
            .findFirst().orElseThrow();
        assertTrue(album.children().stream().anyMatch(node -> node.name().equals("album.mp3")));
        assertTrue(album.children().stream().anyMatch(node -> node.directory() && node.name().equals("disc")));
        var nestedAlbum = artistInFullTree.children().stream()
            .filter(node -> node.directory() && node.name().equals("album"))
            .findFirst()
            .orElseThrow();
        assertTrue(nestedAlbum.children().stream().anyMatch(node -> node.directory() && node.name().equals("disc")));
        assertEquals(4, adapter.findTree("", null).stream().mapToInt(this::countTracks).sum());
        assertFalse(adapter.findTree("../outside", 0).iterator().hasNext());
        assertTrue(adapter.findTrack("artist/album/album.mp3").isPresent());
        assertTrue(adapter.findTrack("../outside.mp3").isEmpty());
        assertTrue(adapter.findTrack("artist/album/cover.jpg").isEmpty());
    }

    private int countTracks(com.atalaya.toolbox.youtube.library.domain.MusicLibraryNode node) {
        return node.directory() ? node.children().stream().mapToInt(this::countTracks).sum() : 1;
    }
}
