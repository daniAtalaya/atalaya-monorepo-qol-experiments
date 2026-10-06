package com.atalaya.toolbox.preferences.backup;

import com.atalaya.toolbox.preferences.service.PlayerThemeService;
import com.atalaya.toolbox.preferences.service.UserPreferencesService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BackupServiceTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    private BackupService service(long limit) {
        return new BackupService(mapper, temp.resolve(".data/preferences").toString(), temp.resolve(".data").toString(), limit);
    }

    private Path write(String relative, byte[] bytes) throws Exception {
        Path file = temp.resolve(".data").resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.write(file, bytes);
    }

    @Test
    void roundTripsAllServicesBinaryFilesEmptyDirectoriesAndFutureNamespacesAndRetainsOldData() throws Exception {
        write("preferences/users.json", "[]".getBytes(StandardCharsets.UTF_8));
        write("series/alex.json", "{\"shows\":[]}".getBytes(StandardCharsets.UTF_8));
        byte[] binary = new byte[] {0, 1, -1, 23, -87};
        write("music/songs/日本語 song.mp3", binary);
        write("future-service/nested/.hidden", binary);
        Files.createDirectories(temp.resolve(".data/future-service/empty"));
        try (BackupServiceScope scope = new BackupServiceScope(service(1_000_000))) {
            BackupService backups = scope.service;
            var backup = backups.createBackup();
            var preview = backups.prepareRestore(backups.download(backup.id()));
            assertEquals(4, preview.fileCount());
            assertTrue(preview.folders().containsAll(List.of("future-service", "music", "preferences", "series")));
            write("music/songs/日本語 song.mp3", new byte[] {99});
            write("future-service/new-file", new byte[] {44});
            var result = backups.restore(preview.id(), () -> {});
            assertArrayEquals(binary, Files.readAllBytes(temp.resolve(".data/music/songs/日本語 song.mp3")));
            assertArrayEquals(binary, Files.readAllBytes(temp.resolve(".data/future-service/nested/.hidden")));
            assertTrue(Files.isDirectory(temp.resolve(".data/future-service/empty")));
            assertFalse(Files.exists(temp.resolve(".data/future-service/new-file")));
            assertArrayEquals(new byte[] {99}, Files.readAllBytes(Path.of(result.recoveryDirectory()).resolve("music/songs/日本語 song.mp3")));
            assertTrue(Files.exists(Path.of(result.recoveryDirectory()).resolve("future-service/new-file")));
        }
    }

    @Test
    void restoreReloadsListenCountsAndDoesNotOverwriteThemWithStaleProfiles() throws Exception {
        String preferencesPath = temp.resolve(".data/preferences").toString();
        var users = new UserPreferencesService(mapper, preferencesPath);
        users.loadProfiles();
        users.selectOrCreate("alex");
        users.recordListen("alex", "songs/a.mp3");
        users.recordListen("alex", "songs/a.mp3");
        var themes = new PlayerThemeService(mapper, users, preferencesPath);
        themes.select("alex", "evergreen");
        try (BackupServiceScope scope = new BackupServiceScope(service(1_000_000))) {
            var backups = scope.service;
            var archive = backups.createBackup();
            users.recordListen("alex", "songs/a.mp3");
            themes.select("alex", "sunroom");
            var preview = backups.prepareRestore(backups.download(archive.id()));
            backups.restore(preview.id(), () -> { users.loadProfiles(); themes.loadSavedThemes(); });
            assertEquals(2, users.listens("alex").getFirst().listenCount());
            assertEquals("evergreen", themes.current("alex").selectedThemeId());
            users.recordListen("alex", "songs/a.mp3");
            var restarted = new UserPreferencesService(mapper, preferencesPath);
            restarted.loadProfiles();
            assertEquals(3, restarted.listens("alex").getFirst().listenCount());
        }
    }

    @Test
    void rollsBackBothTheDirectoryAndCachesIfReloadFails() throws Exception {
        write("preferences/users.json", "[]".getBytes(StandardCharsets.UTF_8));
        Path song = write("music/song.mp3", new byte[] {1});
        try (BackupServiceScope scope = new BackupServiceScope(service(1_000_000))) {
            var backups = scope.service;
            var backup = backups.createBackup();
            var preview = backups.prepareRestore(backups.download(backup.id()));
            Files.write(song, new byte[] {2});
            int[] reloads = {0};
            assertThrows(IllegalArgumentException.class, () -> backups.restore(preview.id(), () -> {
                if (++reloads[0] == 1) throw new IllegalStateException("Invalid preferences");
            }));
            assertArrayEquals(new byte[] {2}, Files.readAllBytes(song));
            assertEquals(2, reloads[0]);
        }
    }

    @Test
    void rejectsTraversalUnexpectedFilesCorruptionAndUnsupportedVersionsBeforeTouchingLiveData() throws Exception {
        Path live = write("preferences/users.json", "[]".getBytes(StandardCharsets.UTF_8));
        try (BackupServiceScope scope = new BackupServiceScope(service(1_000_000))) {
            var backups = scope.service;
            var backup = backups.createBackup();
            Path archive = backups.download(backup.id());
            for (String name : List.of(".data/../../outside.txt", ".data/C:/outside.txt", ".data/series/extra.txt")) {
                Path malicious = rewrite(archive, name, "evil".getBytes(StandardCharsets.UTF_8), false);
                assertThrows(IllegalArgumentException.class, () -> backups.prepareRestore(malicious));
            }
            Path corrupt = rewrite(archive, ".data/preferences/users.json", "[{}]".getBytes(StandardCharsets.UTF_8), false);
            assertThrows(IllegalArgumentException.class, () -> backups.prepareRestore(corrupt));
            Path unsupported = rewrite(archive, "atalaya-backup.json", mapper.writeValueAsBytes(
                new BackupService.Manifest("atalaya-data", 999, "2026-10-06T00:00:00Z", List.of(), List.of())), false);
            assertThrows(IllegalArgumentException.class, () -> backups.prepareRestore(unsupported));
            assertEquals("[]", Files.readString(live));
            assertFalse(Files.exists(temp.resolve("outside.txt")));
            try (var paths = Files.list(temp)) {
                assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".atalaya-restore-")));
            }
        }
    }

    @Test
    void rejectsIncompleteArchivesAndArchivesAboveExpandedLimit() throws Exception {
        write("preferences/users.json", "[]".getBytes(StandardCharsets.UTF_8));
        write("music/song.mp3", new byte[100]);
        try (BackupServiceScope scope = new BackupServiceScope(service(1_000_000));
             BackupServiceScope small = new BackupServiceScope(service(50))) {
            var backup = scope.service.createBackup();
            Path archive = scope.service.download(backup.id());
            Path incomplete = rewrite(archive, ".data/music/song.mp3", null, true);
            assertThrows(IllegalArgumentException.class, () -> scope.service.prepareRestore(incomplete));
            assertThrows(IllegalArgumentException.class, () -> small.service.prepareRestore(archive));
            assertThrows(IllegalArgumentException.class, small.service::createBackup);
        }
    }

    @Test
    void rejectingASafeLookingManifestWithTraversalOrCaseCollisionsDoesNotExtractFiles() throws Exception {
        write("preferences/users.json", "[]".getBytes(StandardCharsets.UTF_8));
        try (BackupServiceScope scope = new BackupServiceScope(service(1_000_000))) {
            var archive = scope.service.download(scope.service.createBackup().id());
            for (List<String> directories : List.of(List.of("../escape"), List.of("series", "SERIES"), List.of("series/CON"))) {
                var manifest = new BackupService.Manifest("atalaya-data", 1, "2026-10-06T00:00:00Z", List.of(), directories);
                Path unsafe = rewrite(archive, "atalaya-backup.json", mapper.writeValueAsBytes(manifest), false);
                assertThrows(IllegalArgumentException.class, () -> scope.service.prepareRestore(unsafe));
            }
        }
    }

    private Path rewrite(Path original, String name, byte[] replacement, boolean omit) throws Exception {
        Path result = Files.createTempFile(temp, "test-archive-", ".zip");
        boolean replaced = false;
        try (ZipFile source = new ZipFile(original.toFile()); ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(result))) {
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.getName().equals(name)) {
                    replaced = true;
                    if (omit) continue;
                }
                zip.putNextEntry(new ZipEntry(entry.getName()));
                if (entry.getName().equals(name)) zip.write(replacement);
                else try (var input = source.getInputStream(entry)) { input.transferTo(zip); }
                zip.closeEntry();
            }
            if (!replaced && !omit) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(replacement);
                zip.closeEntry();
            }
        }
        return result;
    }

    private static class BackupServiceScope implements AutoCloseable {
        final BackupService service;
        BackupServiceScope(BackupService service) { this.service = service; }
        @Override public void close() throws Exception { service.close(); }
    }
}
