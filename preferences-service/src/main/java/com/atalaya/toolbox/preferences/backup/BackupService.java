package com.atalaya.toolbox.preferences.backup;

import com.atalaya.toolbox.preferences.service.UserPreferencesService.StorageOperation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
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
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Versioned filesystem archives: service directories are opaque and never enumerated in code. */
@Service
public class BackupService {
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final String MANIFEST = "atalaya-backup.json";
    private static final String FORMAT = "atalaya-data";
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_MANIFEST_BYTES = 32 * 1024 * 1024;
    private static final long SESSION_SECONDS = 3600;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper;
    private final Path dataDirectory;
    private final long maxExpandedBytes;
    private final Semaphore operation = new Semaphore(1);
    private final Object sessionsLock = new Object();
    private final Object cleanupLock = new Object();
    private final Map<String, Artifact> downloads = new HashMap<>();
    private final Map<String, Artifact> restores = new HashMap<>();
    private final Set<Path> pendingCleanup = new HashSet<>();

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
        log.info("Panic backup configured: dataDirectory={}, maxExpandedBytes={}, sessionSeconds={}",
            dataDirectory, maxExpandedBytes, SESSION_SECONDS);
    }

    public BackupDownload createBackup() throws IOException {
        return runOperation("export", this::export);
    }

    private BackupDownload export(String id) throws IOException {
        Instant createdAt = Instant.now();
        Map<String, SourceStamp> snapshot = scanData();
        Path archive = Files.createTempFile("atalaya-backup-", ".zip");
        List<ArchivedFile> files = new ArrayList<>();
        List<String> directories = new ArrayList<>();
        Progress progress = new Progress(id, "export", snapshot.values().stream().filter(stamp -> !stamp.directory()).count());
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.setLevel(1);
                zip.putNextEntry(new ZipEntry(".data/"));
                zip.closeEntry();
                for (var entry : snapshot.entrySet()) {
                    String relative = entry.getKey();
                    if (relative.isEmpty()) continue;
                    SourceStamp stamp = entry.getValue();
                    Path source = dataDirectory.resolve(relative);
                    if (stamp.directory()) {
                        directories.add(relative);
                        zip.putNextEntry(new ZipEntry(".data/" + relative + "/"));
                    } else {
                        requireUnchanged(stamp, source);
                        zip.putNextEntry(new ZipEntry(".data/" + relative));
                        DigestResult digest;
                        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
                            digest = copyWithDigest(input, zip, maxExpandedBytes - progress.bytes, progress);
                        }
                        requireUnchanged(stamp, source);
                        if (stamp.size() != digest.size()) throw dataChanged();
                        files.add(new ArchivedFile(relative, digest.size(), digest.sha256()));
                        progress.fileCompleted();
                    }
                    zip.closeEntry();
                }
                // Recheck the whole inventory, including files already copied and added/deleted paths.
                if (!snapshot.equals(scanData())) throw dataChanged();
                byte[] manifest = mapper.writeValueAsBytes(new Manifest(FORMAT, 1, createdAt.toString(), files, directories));
                if (manifest.length > MAX_MANIFEST_BYTES) throw new IllegalArgumentException("Backup manifest is too large.");
                zip.putNextEntry(new ZipEntry(MANIFEST));
                zip.write(manifest);
                zip.closeEntry();
            }
            long size = Files.size(archive);
            synchronized (sessionsLock) {
                downloads.put(id, new Artifact(archive, createdAt, null, size));
            }
            log.info("Panic backup {} ready: files={}, expandedBytes={}, archiveBytes={}", id, files.size(), progress.bytes, size);
            return new BackupDownload(id, fileName(createdAt), size, files.size(), createdAt.toString());
        } catch (IOException | RuntimeException exception) {
            cleanupAfterFailure(archive, exception);
            throw exception;
        }
    }

    /** Extracts and verifies everything outside the live data tree. No live files change here. */
    public RestorePreview prepareRestore(Path uploadedZip) throws IOException {
        return runOperation("verify", id -> verify(id, uploadedZip));
    }

    private RestorePreview verify(String id, Path uploadedZip) throws IOException {
        validateRoot();
        Path stage = Files.createTempDirectory(dataDirectory.getParent(), ".atalaya-restore-");
        Manifest manifest;
        long expectedBytes = 0;
        try {
            try (ZipFile zip = new ZipFile(uploadedZip.toFile())) {
                if (zip.size() > MAX_ENTRIES + 2) throw new IllegalArgumentException("Backup contains too many entries.");
                ZipEntry metadata = zip.getEntry(MANIFEST);
                if (metadata == null || metadata.isDirectory() || metadata.getSize() > MAX_MANIFEST_BYTES) {
                    throw new IllegalArgumentException("Choose an Atalaya panic backup ZIP with a valid manifest.");
                }
                try (InputStream input = zip.getInputStream(metadata)) {
                    byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
                    if (bytes.length > MAX_MANIFEST_BYTES) throw new IllegalArgumentException("Backup manifest is too large.");
                    manifest = mapper.readValue(bytes, Manifest.class);
                }
                if (manifest == null || !FORMAT.equals(manifest.format()) || manifest.version() != 1
                    || manifest.files() == null || manifest.directories() == null || manifest.createdAt() == null) {
                    throw new IllegalArgumentException("This backup format/version is not supported.");
                }
                try {
                    Instant.parse(manifest.createdAt());
                } catch (DateTimeException exception) {
                    throw new IllegalArgumentException("Invalid backup creation date.", exception);
                }
                checkEntryCount((long) manifest.files().size() + manifest.directories().size());
                Map<String, ArchivedFile> expected = new HashMap<>();
                Set<String> names = new HashSet<>();
                Set<String> expectedEntries = new HashSet<>(Set.of(MANIFEST, ".data/"));
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
                Set<String> directories = new HashSet<>(manifest.directories());
                for (String path : expected.keySet()) validateParents(path, directories);
                for (String path : directories) validateParents(path, directories);

                Path restoredData = Files.createDirectory(stage.resolve(".data"));
                Set<String> seenEntries = new HashSet<>();
                Progress progress = new Progress(id, "verify", expected.size());
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!seenEntries.add(name) || !expectedEntries.contains(name)) {
                        throw new IllegalArgumentException("Unexpected or duplicate entry in backup: " + name);
                    }
                    if (name.equals(MANIFEST) || name.equals(".data/")) continue;
                    String relative = name.substring(".data/".length());
                    Path destination = restoredData.resolve(relative).normalize();
                    if (!destination.startsWith(restoredData)) throw new IllegalArgumentException("Unsafe backup path.");
                    if (entry.isDirectory()) {
                        Files.createDirectories(destination);
                        continue;
                    }
                    ArchivedFile file = expected.get(relative);
                    if (entry.getSize() != file.size()) {
                        throw new IllegalArgumentException("Backup file size does not match its manifest: " + relative);
                    }
                    Files.createDirectories(destination.getParent());
                    DigestResult digest;
                    try (InputStream input = zip.getInputStream(entry); OutputStream output = Files.newOutputStream(destination)) {
                        digest = copyWithDigest(input, output, file.size(), progress);
                    }
                    if (digest.size() != file.size() || !digest.sha256().equals(file.sha256())) {
                        throw new IllegalArgumentException("Backup checksum failed: " + relative);
                    }
                    progress.fileCompleted();
                }
                if (!seenEntries.equals(expectedEntries)) throw new IllegalArgumentException("Backup is incomplete.");
            }
            // Publish only after the ZIP has also closed successfully.
            synchronized (sessionsLock) {
                restores.put(id, new Artifact(stage, Instant.now(), manifest, 0));
            }
            Set<String> folders = new HashSet<>();
            manifest.files().forEach(file -> folders.add(file.path().split("/", 2)[0]));
            manifest.directories().forEach(name -> folders.add(name.split("/", 2)[0]));
            log.info("Panic restore {} verified: files={}, expandedBytes={}, stage={}", id, manifest.files().size(), expectedBytes, stage);
            return new RestorePreview(id, manifest.createdAt(), manifest.files().size(), expectedBytes, folders.stream().sorted().toList());
        } catch (IOException | RuntimeException exception) {
            cleanupAfterFailure(stage, exception);
            if (exception instanceof ZipException || exception instanceof JsonProcessingException) {
                throw new IllegalArgumentException("Could not read this backup ZIP or manifest. It may be damaged or incomplete.", exception);
            }
            throw exception;
        }
    }

    public RestoreResult restore(String id, Runnable reloadPreferences) throws IOException {
        return restore(id, reloadPreferences, StorageOperation::run);
    }

    public RestoreResult restore(String id, Runnable reloadPreferences, RestoreLock storageLock) throws IOException {
        // Claim the operation before waiting for preferences, so competitors fail fast.
        return runOperation("restore", id, operationId -> storageLock.run(() -> {
            Artifact artifact = acquireArtifact(restores, id);
            try {
                return replaceData(id, artifact, reloadPreferences);
            } finally {
                releaseArtifact(artifact);
            }
        }));
    }

    private RestoreResult replaceData(String id, Artifact artifact, Runnable reloadPreferences) throws IOException {
        validateRoot();
        Path oldData = dataDirectory.resolveSibling(dataDirectory.getFileName() + "-before-restore-"
            + TIMESTAMP.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
        boolean hadData = Files.exists(dataDirectory, LinkOption.NOFOLLOW_LINKS);
        boolean movedNewData = false;
        boolean movedOldData = false;
        log.info("Panic restore {} replacing data: stage={}, recoveryDirectory={}", id, artifact.path, oldData);
        try {
            if (hadData) {
                Files.move(dataDirectory, oldData);
                movedOldData = true;
            }
            Files.move(artifact.path.resolve(".data"), dataDirectory);
            movedNewData = true;
            reloadPreferences.run();
        } catch (IOException | RuntimeException exception) {
            log.warn("Panic restore {} failed; rolling back directory and preferences", id, exception);
            try {
                if (movedNewData) Files.move(dataDirectory, artifact.path.resolve(".data"));
                if (movedOldData) Files.move(oldData, dataDirectory);
                if (movedNewData || movedOldData) reloadPreferences.run();
            } catch (IOException | RuntimeException rollback) {
                exception.addSuppressed(rollback);
                // Neither the staged nor previous tree may be expired/deleted after an incomplete rollback.
                synchronized (sessionsLock) {
                    restores.remove(id);
                }
                log.error("Panic restore {} rollback failed. Preserve live={}, previous={}, staged={}",
                    id, dataDirectory, oldData, artifact.path, exception);
                throw new RecoveryException("Restore failed and automatic rollback could not finish. Stop all services. "
                    + "Preserve previous data at " + oldData + " and staged data at " + artifact.path + ".", exception);
            }
            log.info("Panic restore {} rolled back; original data and preferences reloaded", id);
            throw new IllegalArgumentException("Restore failed; previous data was kept. Stop other services and check the backup before retrying.", exception);
        }
        synchronized (sessionsLock) {
            restores.remove(id);
        }
        cleanupTemporaryLater(artifact.path);
        log.info("Panic restore {} complete: files={}, recoveryDirectory={}", id, artifact.manifest.files().size(), hadData ? oldData : null);
        return new RestoreResult(artifact.manifest.files().size(), hadData ? oldData.toString() : null, true);
    }

    public DownloadDetails downloadDetails(String id) {
        synchronized (sessionsLock) {
            Artifact artifact = requireArtifact(downloads, id);
            // Reserve time for MVC to dispatch the streaming callback after headers are prepared.
            artifact.expiresAt = Instant.now().plusSeconds(SESSION_SECONDS);
            return new DownloadDetails(fileName(artifact.createdAt), artifact.sizeBytes);
        }
    }

    public Path download(String id) {
        synchronized (sessionsLock) {
            return requireArtifact(downloads, id).path;
        }
    }

    public String downloadFileName(String id) {
        return downloadDetails(id).fileName();
    }

    public void streamDownload(String id, OutputStream output) throws IOException {
        Artifact artifact = acquireArtifact(downloads, id);
        long started = System.nanoTime();
        log.info("Panic backup {} download started: archiveBytes={}", id, artifact.sizeBytes);
        try {
            long bytes = Files.copy(artifact.path, output);
            output.flush();
            log.info("Panic backup {} download streamed: bytes={}, durationMs={}", id, bytes, elapsedMillis(started));
        } catch (IOException | RuntimeException exception) {
            log.warn("Panic backup {} download interrupted; archive retained for retry", id, exception);
            throw exception;
        } finally {
            releaseArtifact(artifact);
        }
    }

    public void discardDownload(String id) throws IOException {
        discard(downloads, id, false);
    }

    public void discardRestore(String id) throws IOException {
        discard(restores, id, false);
    }

    void discardUpload(Path upload) {
        cleanupTemporaryLater(upload);
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanupExpired() throws IOException {
        synchronized (cleanupLock) {
            List<String> downloadIds;
            List<String> restoreIds;
            List<Path> cleanupPaths;
            synchronized (sessionsLock) {
                downloadIds = List.copyOf(downloads.keySet());
                restoreIds = List.copyOf(restores.keySet());
                cleanupPaths = List.copyOf(pendingCleanup);
            }
            IOException failure = null;
            for (String id : downloadIds) {
                try { discard(downloads, id, true); }
                catch (IOException exception) { failure = accumulate(failure, exception); }
            }
            for (String id : restoreIds) {
                try { discard(restores, id, true); }
                catch (IOException exception) { failure = accumulate(failure, exception); }
            }
            for (Path path : cleanupPaths) {
                try {
                    deleteTemporary(path);
                    synchronized (sessionsLock) { pendingCleanup.remove(path); }
                } catch (IOException exception) {
                    log.warn("Panic backup temporary cleanup failed; will retry: {}", path, exception);
                    failure = accumulate(failure, exception);
                }
            }
            if (failure != null) throw failure;
        }
    }

    @PreDestroy
    public void close() throws IOException {
        operation.acquireUninterruptibly();
        try {
            synchronized (sessionsLock) {
                downloads.values().forEach(artifact -> artifact.expiresAt = Instant.EPOCH);
                restores.values().forEach(artifact -> artifact.expiresAt = Instant.EPOCH);
            }
            cleanupExpired();
        } finally {
            operation.release();
        }
    }

    private void discard(Map<String, Artifact> artifacts, String id, boolean expiredOnly) throws IOException {
        Artifact artifact;
        synchronized (sessionsLock) {
            artifact = artifacts.get(id);
            if (artifact == null || artifact.deleting) return;
            if (expiredOnly && artifact.expiresAt.isAfter(Instant.now())) return;
            if (artifact.activeUsers > 0) {
                if (!expiredOnly) throw new BusyException("This backup session is in use. Wait for the operation to finish.");
                return;
            }
            artifact.deleting = true;
        }
        try {
            deleteTemporary(artifact.path);
            synchronized (sessionsLock) { artifacts.remove(id, artifact); }
            log.info("Panic backup session {} {} and cleaned up", id, expiredOnly ? "expired" : "discarded");
        } catch (IOException exception) {
            synchronized (sessionsLock) {
                artifact.deleting = false;
                artifact.expiresAt = Instant.EPOCH;
            }
            log.warn("Panic backup session {} cleanup failed; will retry: {}", id, artifact.path, exception);
            throw exception;
        }
    }

    private Artifact acquireArtifact(Map<String, Artifact> artifacts, String id) {
        synchronized (sessionsLock) {
            Artifact artifact = requireArtifact(artifacts, id);
            artifact.activeUsers++;
            return artifact;
        }
    }

    private void releaseArtifact(Artifact artifact) {
        synchronized (sessionsLock) {
            artifact.activeUsers--;
            artifact.expiresAt = Instant.now().plusSeconds(SESSION_SECONDS);
        }
    }

    private Artifact requireArtifact(Map<String, Artifact> artifacts, String id) {
        Artifact artifact = artifacts.get(id);
        if (artifact == null || artifact.deleting || !artifact.expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("Backup session expired. Please prepare the backup again.");
        }
        return artifact;
    }

    private <T> T runOperation(String phase, BackupOperation<T> work) throws IOException {
        return runOperation(phase, UUID.randomUUID().toString(), work);
    }

    private <T> T runOperation(String phase, String id, BackupOperation<T> work) throws IOException {
        if (!operation.tryAcquire()) {
            log.warn("Panic backup {} rejected: another archive operation is running", phase);
            throw new BusyException("Another backup or restore operation is running. Wait for it to finish and try again.");
        }
        long started = System.nanoTime();
        log.info("Panic backup {} {} started: dataDirectory={}", id, phase, dataDirectory);
        try {
            T result = work.run(id);
            log.info("Panic backup {} {} finished: durationMs={}", id, phase, elapsedMillis(started));
            return result;
        } catch (IOException | RuntimeException exception) {
            log.error("Panic backup {} {} failed: durationMs={}", id, phase, elapsedMillis(started), exception);
            throw exception;
        } finally {
            operation.release();
        }
    }

    private Map<String, SourceStamp> scanData() throws IOException {
        validateRoot();
        if (!Files.isDirectory(dataDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("The shared data directory does not exist or is not a directory.");
        }
        Map<String, SourceStamp> snapshot = new TreeMap<>();
        Set<String> names = new HashSet<>();
        Files.walkFileTree(dataDirectory, new SimpleFileVisitor<>() {
            private void register(Path path, BasicFileAttributes attributes) {
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(path)
                    || (!attributes.isDirectory() && !attributes.isRegularFile())) {
                    throw new IllegalArgumentException("Backup cannot include links or special files: " + path);
                }
                String relative = dataDirectory.relativize(path).toString().replace('\\', '/');
                if (!relative.isEmpty()) registerName(names, relative);
                checkEntryCount(names.size());
                snapshot.put(relative, SourceStamp.from(attributes));
            }
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                register(directory, attributes);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                register(file, attributes);
                return FileVisitResult.CONTINUE;
            }
        });
        return snapshot;
    }

    private void validateRoot() {
        for (Path path = dataDirectory; path != null; path = path.getParent()) {
            if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("The shared data directory cannot contain linked ancestors.");
        }
    }

    private static void requireUnchanged(SourceStamp expected, Path file) throws IOException {
        if (!expected.equals(SourceStamp.from(Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)))) {
            throw dataChanged();
        }
    }

    private static IllegalArgumentException dataChanged() {
        return new IllegalArgumentException("Data changed during backup. Pause downloads and other changes, then try again.");
    }

    private void cleanupAfterFailure(Path path, Exception original) {
        try {
            deleteTemporary(path);
        } catch (IOException cleanup) {
            original.addSuppressed(cleanup);
            synchronized (sessionsLock) { pendingCleanup.add(path); }
            log.warn("Panic backup failure cleanup could not finish; will retry: {}", path, cleanup);
        }
    }

    private void cleanupTemporaryLater(Path path) {
        try {
            deleteTemporary(path);
        } catch (IOException cleanup) {
            synchronized (sessionsLock) { pendingCleanup.add(path); }
            log.warn("Panic backup temporary cleanup failed; scheduled retry: {}", path, cleanup);
        }
    }

    private static IOException accumulate(IOException failure, IOException exception) {
        if (failure == null) return exception;
        failure.addSuppressed(exception);
        return failure;
    }

    private static void validateParents(String path, Set<String> directories) {
        int separator = path.lastIndexOf('/');
        if (separator >= 0 && !directories.contains(path.substring(0, separator))) {
            throw new IllegalArgumentException("Backup path has a missing or conflicting parent directory: " + path);
        }
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
                || segment.matches(".*[<>\"|?*\\p{Cntrl}].*")
                || segment.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) {
                throw new IllegalArgumentException("Unsafe path in backup: " + path);
            }
        }
        Path.of(path);
    }

    private static void checkEntryCount(long count) {
        if (count > MAX_ENTRIES) throw new IllegalArgumentException("Backup contains too many entries.");
    }

    private static DigestResult copyWithDigest(InputStream input, OutputStream output, long limit, Progress progress) throws IOException {
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
            progress.copied(read);
        }
        return new DigestResult(size, HexFormat.of().formatHex(digest.digest()));
    }

    private static void deleteTemporary(Path root) throws IOException {
        try {
            Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return;
        }
        // Only service-created temporary paths reach here; links are never followed.
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

    private static String fileName(Instant createdAt) {
        return "atalaya-panic-backup-" + TIMESTAMP.format(createdAt) + ".zip";
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static final class Progress {
        private final String id;
        private final String phase;
        private final long totalFiles;
        private long files;
        private long bytes;
        private long lastLog = System.nanoTime();

        private Progress(String id, String phase, long totalFiles) {
            this.id = id;
            this.phase = phase;
            this.totalFiles = totalFiles;
        }

        private void copied(int count) {
            bytes += count;
            if (elapsedMillis(lastLog) >= 10_000) {
                log.info("Panic backup {} {} progress: files={}/{}, bytes={}", id, phase, files, totalFiles, bytes);
                lastLog = System.nanoTime();
            }
        }

        private void fileCompleted() { files++; }
    }

    private static final class Artifact {
        private final Path path;
        private final Instant createdAt;
        private final Manifest manifest;
        private final long sizeBytes;
        // Mutable lifecycle fields are guarded only by sessionsLock; file I/O never holds it.
        private Instant expiresAt = Instant.now().plusSeconds(SESSION_SECONDS);
        private int activeUsers;
        private boolean deleting;

        private Artifact(Path path, Instant createdAt, Manifest manifest, long sizeBytes) {
            this.path = path;
            this.createdAt = createdAt;
            this.manifest = manifest;
            this.sizeBytes = sizeBytes;
        }
    }

    private record SourceStamp(boolean directory, boolean regularFile, long size, FileTime modifiedAt, Object fileKey) {
        private static SourceStamp from(BasicFileAttributes attributes) {
            return new SourceStamp(attributes.isDirectory(), attributes.isRegularFile(),
                attributes.isDirectory() ? 0 : attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
        }
    }

    @FunctionalInterface
    private interface BackupOperation<T> { T run(String id) throws IOException; }

    @FunctionalInterface
    public interface RestoreLock {
        RestoreResult run(StorageOperation<RestoreResult> restore) throws IOException;
    }

    public static class BusyException extends RuntimeException {
        public BusyException(String message) { super(message); }
    }

    public static class RecoveryException extends IllegalStateException {
        public RecoveryException(String message, Throwable cause) { super(message, cause); }
    }

    public record ArchivedFile(String path, long size, String sha256) {}
    public record Manifest(String format, int version, String createdAt, List<ArchivedFile> files, List<String> directories) {}
    public record BackupDownload(String id, String fileName, long sizeBytes, int fileCount, String createdAt) {}
    public record DownloadDetails(String fileName, long sizeBytes) {}
    public record RestorePreview(String id, String createdAt, int fileCount, long sizeBytes, List<String> folders) {}
    public record RestoreResult(int fileCount, String recoveryDirectory, boolean restartServices) {}
    private record DigestResult(long size, String sha256) {}
}
