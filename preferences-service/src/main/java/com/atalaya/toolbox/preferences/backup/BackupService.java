package com.atalaya.toolbox.preferences.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.DateTimeException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Versioned filesystem archives: service directories are opaque and never enumerated in code. */
@Service
public class BackupService {
    private static final String MANIFEST = "atalaya-backup.json";
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_MANIFEST_BYTES = 32 * 1024 * 1024;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final String FORMAT = "atalaya-data";
    private final ObjectMapper mapper;
    private final Path dataDirectory;
    private final long maxExpandedBytes;
    private final Map<String, Artifact> downloads = new HashMap<>();
    private final Map<String, Artifact> restores = new HashMap<>();

    public BackupService(ObjectMapper mapper,
        @Value("${toolbox.preferences.storage-directory:../.data/preferences}") String preferencesDirectory,
        @Value("${toolbox.backup.data-directory:}") String configuredDataDirectory,
        @Value("${toolbox.backup.max-expanded-bytes:1099511627776}") long maxExpandedBytes) {
        this.mapper = mapper;
        Path preferences = Path.of(preferencesDirectory).toAbsolutePath().normalize();
        this.dataDirectory = configuredDataDirectory.isBlank() ? preferences.getParent()
            : Path.of(configuredDataDirectory).toAbsolutePath().normalize();
        if (dataDirectory == null || dataDirectory.getParent() == null || dataDirectory.getNameCount() < 2
            || !preferences.startsWith(dataDirectory) || preferences.equals(dataDirectory) || maxExpandedBytes < 1) {
            throw new IllegalArgumentException("Backup root must contain the preferences directory and cannot be a filesystem root.");
        }
        this.maxExpandedBytes = maxExpandedBytes;
    }

    public synchronized BackupDownload createBackup() throws IOException {
        cleanupExpired();
        if (!Files.isDirectory(dataDirectory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(dataDirectory)) {
            throw new IllegalArgumentException("The shared data directory does not exist or is a symbolic link.");
        }
        Instant now = Instant.now();
        String fileName = "atalaya-panic-backup-" + TIMESTAMP.format(now) + ".zip";
        Path archive = Files.createTempFile("atalaya-backup-", ".zip");
        List<ArchivedFile> files = new ArrayList<>();
        Set<String> directories = new HashSet<>();
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.setLevel(1); // MP3s/covers are already compressed; favor a quick panic backup.
                zip.putNextEntry(new ZipEntry(".data/"));
                zip.closeEntry();
                Files.walkFileTree(dataDirectory, new SimpleFileVisitor<>() {
                    private int entries;
                    private long totalBytes;

                    private String relative(Path path) {
                        return dataDirectory.relativize(path).toString().replace('\\', '/');
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                        if (Files.isSymbolicLink(directory) || attributes.isSymbolicLink()) {
                            throw new IllegalArgumentException("Backup cannot include linked directories.");
                        }
                        if (directory.equals(dataDirectory)) return FileVisitResult.CONTINUE;
                        checkEntryCount(++entries);
                        String path = relative(directory);
                        validateRelative(path);
                        directories.add(path);
                        zip.putNextEntry(new ZipEntry(".data/" + path + "/"));
                        zip.closeEntry();
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || Files.isSymbolicLink(file)) {
                            throw new IllegalArgumentException("Backup cannot include links or special files: " + relative(file));
                        }
                        checkEntryCount(++entries);
                        String path = relative(file);
                        validateRelative(path);
                        zip.putNextEntry(new ZipEntry(".data/" + path));
                        DigestResult digest;
                        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                            digest = copyWithDigest(input, zip, maxExpandedBytes - totalBytes);
                        }
                        zip.closeEntry();
                        totalBytes += digest.size();
                        if (attributes.size() != digest.size()
                            || !attributes.lastModifiedTime().equals(Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS))) {
                            throw new IllegalArgumentException("Data changed during backup. Pause downloads and other changes, then try again.");
                        }
                        files.add(new ArchivedFile(path, digest.size(), digest.sha256()));
                        return FileVisitResult.CONTINUE;
                    }
                });
                byte[] manifest = mapper.writeValueAsBytes(new Manifest(FORMAT, 1, now.toString(), files,
                    directories.stream().sorted().toList()));
                if (manifest.length > MAX_MANIFEST_BYTES) throw new IllegalArgumentException("Backup manifest is too large.");
                zip.putNextEntry(new ZipEntry(MANIFEST));
                zip.write(manifest);
                zip.closeEntry();
            }
            String id = UUID.randomUUID().toString();
            downloads.put(id, new Artifact(archive, now, null));
            return new BackupDownload(id, fileName, Files.size(archive), files.size(), now.toString());
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(archive);
            throw exception;
        }
    }

    /** Extracts and verifies everything outside the live data tree. No live files change here. */
    public synchronized RestorePreview prepareRestore(Path uploadedZip) throws IOException {
        cleanupExpired();
        Path stage = Files.createTempDirectory(dataDirectory.getParent(), ".atalaya-restore-");
        try (ZipFile zip = new ZipFile(uploadedZip.toFile())) {
            if (zip.size() > MAX_ENTRIES + 2) throw new IllegalArgumentException("Backup contains too many entries.");
            ZipEntry metadata = zip.getEntry(MANIFEST);
            if (metadata == null || metadata.isDirectory() || metadata.getSize() > MAX_MANIFEST_BYTES) {
                throw new IllegalArgumentException("Choose an Atalaya panic backup ZIP with a valid manifest.");
            }
            Manifest manifest;
            try (InputStream input = zip.getInputStream(metadata)) {
                byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
                if (bytes.length > MAX_MANIFEST_BYTES) throw new IllegalArgumentException("Backup manifest is too large.");
                manifest = mapper.readValue(bytes, Manifest.class);
            }
            if (!FORMAT.equals(manifest.format()) || manifest.version() != 1 || manifest.files() == null
                || manifest.directories() == null || manifest.createdAt() == null) {
                throw new IllegalArgumentException("This backup format/version is not supported.");
            }
            try {
                Instant.parse(manifest.createdAt());
            } catch (DateTimeException exception) {
                throw new IllegalArgumentException("Invalid backup creation date.", exception);
            }
            checkEntryCount(manifest.files().size() + manifest.directories().size());
            Path restoredData = Files.createDirectory(stage.resolve(".data"));
            Map<String, ArchivedFile> expected = new HashMap<>();
            Set<String> names = new HashSet<>();
            Set<String> expectedEntries = new HashSet<>(Set.of(MANIFEST, ".data/"));
            long expectedBytes = 0;
            for (ArchivedFile file : manifest.files()) {
                if (file == null) throw new IllegalArgumentException("Invalid file entry in backup manifest.");
                registerName(names, file.path());
                if (file.size() < 0 || file.size() > maxExpandedBytes - expectedBytes
                    || file.sha256() == null || !file.sha256().matches("[a-f0-9]{64}")) {
                    throw new IllegalArgumentException("Invalid file size or checksum in backup manifest.");
                }
                expectedBytes += file.size();
                expected.put(file.path(), file);
                expectedEntries.add(".data/" + file.path());
            }
            for (String directory : manifest.directories()) {
                registerName(names, directory);
                expectedEntries.add(".data/" + directory + "/");
            }
            Set<String> seenEntries = new HashSet<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!seenEntries.add(name) || !expectedEntries.contains(name)) {
                    throw new IllegalArgumentException("Unexpected or duplicate entry in backup: " + name);
                }
                if (name.equals(MANIFEST) || name.equals(".data/")) continue;
                String relative = name.substring(".data/".length());
                if (entry.isDirectory()) {
                    Files.createDirectories(restoredData.resolve(relative));
                    continue;
                }
                ArchivedFile file = expected.get(relative);
                if (entry.getSize() != file.size()) throw new IllegalArgumentException("Backup file size does not match its manifest: " + relative);
                Path destination = restoredData.resolve(relative).normalize();
                if (!destination.startsWith(restoredData)) throw new IllegalArgumentException("Unsafe backup path.");
                Files.createDirectories(destination.getParent());
                DigestResult digest;
                try (InputStream input = zip.getInputStream(entry); OutputStream output = Files.newOutputStream(destination)) {
                    digest = copyWithDigest(input, output, file.size());
                }
                if (digest.size() != file.size() || !digest.sha256().equals(file.sha256())) {
                    throw new IllegalArgumentException("Backup checksum failed: " + relative);
                }
            }
            if (!seenEntries.equals(expectedEntries)) throw new IllegalArgumentException("Backup is incomplete.");
            String id = UUID.randomUUID().toString();
            restores.put(id, new Artifact(stage, Instant.now(), manifest));
            Set<String> folders = new HashSet<>();
            manifest.files().forEach(file -> folders.add(file.path().split("/", 2)[0]));
            manifest.directories().forEach(name -> folders.add(name.split("/", 2)[0]));
            return new RestorePreview(id, manifest.createdAt(), manifest.files().size(), expectedBytes,
                folders.stream().sorted().toList());
        } catch (IOException | RuntimeException exception) {
            deleteTemporaryTree(stage);
            if (exception instanceof IOException) {
                throw new IllegalArgumentException("Could not read this backup ZIP. It may be damaged or incomplete.", exception);
            }
            throw exception;
        }
    }

    /** Same-filesystem directory moves; keep the old tree and roll back if local caches cannot reload. */
    public synchronized RestoreResult restore(String id, Runnable reloadPreferences) throws IOException {
        Artifact artifact = requireArtifact(restores, id);
        Path oldData = dataDirectory.resolveSibling(dataDirectory.getFileName() + "-before-restore-"
            + TIMESTAMP.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
        boolean hadData = Files.exists(dataDirectory, LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(dataDirectory)) throw new IllegalArgumentException("Cannot restore over a symbolic link.");
        boolean movedNewData = false;
        boolean movedOldData = false;
        try {
            if (hadData) {
                Files.move(dataDirectory, oldData);
                movedOldData = true;
            }
            Files.move(artifact.path().resolve(".data"), dataDirectory);
            movedNewData = true;
            reloadPreferences.run();
        } catch (IOException | RuntimeException exception) {
            try {
                if (movedNewData) Files.move(dataDirectory, artifact.path().resolve(".data"));
                if (movedOldData) Files.move(oldData, dataDirectory);
                reloadPreferences.run();
            } catch (IOException | RuntimeException rollback) {
                exception.addSuppressed(rollback);
                throw new IllegalStateException("Restore failed and automatic rollback could not finish. Previous data is at " + oldData, exception);
            }
            throw new IllegalArgumentException("Restore failed; previous data was kept. Stop other services and check the backup before retrying.", exception);
        }
        restores.remove(id);
        deleteTemporaryTree(artifact.path());
        return new RestoreResult(artifact.manifest().files().size(), hadData ? oldData.toString() : null, true);
    }

    public synchronized Path download(String id) {
        return requireArtifact(downloads, id).path();
    }

    public synchronized String downloadFileName(String id) {
        return "atalaya-panic-backup-" + TIMESTAMP.format(requireArtifact(downloads, id).createdAt()) + ".zip";
    }

    public synchronized void discardDownload(String id) throws IOException {
        Artifact artifact = downloads.remove(id);
        if (artifact != null) Files.deleteIfExists(artifact.path());
    }

    public synchronized void discardRestore(String id) throws IOException {
        Artifact artifact = restores.remove(id);
        if (artifact != null) deleteTemporaryTree(artifact.path());
    }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void cleanupExpired() throws IOException {
        Instant cutoff = Instant.now().minusSeconds(3600);
        for (String id : List.copyOf(downloads.keySet())) {
            if (downloads.get(id).createdAt().isBefore(cutoff)) discardDownload(id);
        }
        for (String id : List.copyOf(restores.keySet())) {
            if (restores.get(id).createdAt().isBefore(cutoff)) discardRestore(id);
        }
    }

    @PreDestroy
    public synchronized void close() throws IOException {
        for (String id : List.copyOf(downloads.keySet())) discardDownload(id);
        for (String id : List.copyOf(restores.keySet())) discardRestore(id);
    }

    private Artifact requireArtifact(Map<String, Artifact> artifacts, String id) {
        Artifact artifact = artifacts.get(id);
        if (artifact == null || artifact.createdAt().isBefore(Instant.now().minusSeconds(3600))) {
            throw new IllegalArgumentException("Backup session expired. Please prepare the backup again.");
        }
        return artifact;
    }

    private static void registerName(Set<String> names, String path) {
        validateRelative(path);
        if (!names.add(path.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Duplicate backup path: " + path);
    }

    private static void validateRelative(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains(":")) {
            throw new IllegalArgumentException("Unsafe path in backup.");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..") || segment.endsWith(".") || segment.endsWith(" ")
                || segment.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) {
                throw new IllegalArgumentException("Unsafe path in backup: " + path);
            }
        }
        Path.of(path); // Validate characters for the host filesystem as well.
    }

    private static void checkEntryCount(int count) {
        if (count > MAX_ENTRIES) throw new IllegalArgumentException("Backup contains too many entries.");
    }

    private static DigestResult copyWithDigest(InputStream input, OutputStream output, long limit) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        byte[] buffer = new byte[64 * 1024];
        long size = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > limit - size) throw new IllegalArgumentException("Backup exceeds the configured expanded size or manifest file size.");
            output.write(buffer, 0, read);
            digest.update(buffer, 0, read);
            size += read;
        }
        return new DigestResult(size, HexFormat.of().formatHex(digest.digest()));
    }

    private static void deleteTemporaryTree(Path root) throws IOException {
        // Only called with temporary paths created by this service; links are never followed.
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public record ArchivedFile(String path, long size, String sha256) {}
    public record Manifest(String format, int version, String createdAt, List<ArchivedFile> files, List<String> directories) {}
    public record BackupDownload(String id, String fileName, long sizeBytes, int fileCount, String createdAt) {}
    public record RestorePreview(String id, String createdAt, int fileCount, long sizeBytes, List<String> folders) {}
    public record RestoreResult(int fileCount, String recoveryDirectory, boolean restartServices) {}
    private record Artifact(Path path, Instant createdAt, Manifest manifest) {}
    private record DigestResult(long size, String sha256) {}
}
