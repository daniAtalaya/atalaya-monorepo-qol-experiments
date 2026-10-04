package com.atalaya.toolbox.youtube.history.adapter;

import com.atalaya.toolbox.youtube.history.repository.DownloadHistoryRepository;
import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import com.atalaya.toolbox.youtube.history.domain.DownloadHistoryEntry;
import com.atalaya.toolbox.youtube.download.domain.YoutubeDownloadException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Repository
public class JsonDownloadHistoryAdapter implements DownloadHistoryRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(JsonDownloadHistoryAdapter.class);
    private static final String HISTORY_FILENAME = "download-history.json";
    private static final String INACCESSIBLE_METADATA_FILENAME = "inaccessible-video-metadata.json";
    private static final Pattern DAILY_HISTORY_FILENAME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}\\.json");
    private static final Pattern LEGACY_HISTORY_FILENAME = Pattern.compile("[A-Za-z0-9_-]{1,128}\\.json");
    private static final TypeReference<List<DownloadHistoryEntry>> DAILY_HISTORY_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, List<DownloadHistoryEntry>>> LEGACY_HISTORY_TYPE = new TypeReference<>() {};
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final YoutubeProperties properties;
    private final ObjectMapper objectMapper;

    public JsonDownloadHistoryAdapter(YoutubeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(DownloadHistoryEntry entry) {
        Path historyDirectory = historyDirectory();
        try {
            Files.createDirectories(historyDirectory);
            withHistoryLock(historyDirectory, () -> {
                migrateLegacyHistory(historyDirectory);
                String day = dayFor(entry);
                List<DownloadHistoryEntry> entries = readDailyHistory(historyDirectory).computeIfAbsent(day, ignored -> new ArrayList<>());
                entries.add(entry);
                writeDailyHistory(historyDirectory, day, entries);
                LOGGER.info("Saved '{}' to {} ({} download(s) on {})", entry.name(), dailyHistoryFile(historyDirectory, day), entries.size(), day);
                return null;
            });
        } catch (IOException e) {
            LOGGER.error("Failed to save download history for '{}'", entry.name(), e);
            throw new YoutubeDownloadException("No se pudo guardar el historial de descargas JSON.", e);
        }
    }

    @Override
    public void replaceByVideoId(DownloadHistoryEntry entry) {
        Path historyDirectory = historyDirectory();
        try {
            Files.createDirectories(historyDirectory);
            withHistoryLock(historyDirectory, () -> {
                migrateLegacyHistory(historyDirectory);
                Map<String, List<DownloadHistoryEntry>> history = readDailyHistory(historyDirectory);
                for (Map.Entry<String, List<DownloadHistoryEntry>> day : history.entrySet()) {
                    List<DownloadHistoryEntry> entries = day.getValue();
                    entries.removeIf(existing -> Objects.equals(existing.videoId(), entry.videoId()));
                    if (entries.isEmpty()) {
                        Files.deleteIfExists(dailyHistoryFile(historyDirectory, day.getKey()));
                    } else {
                        writeDailyHistory(historyDirectory, day.getKey(), entries);
                    }
                }
                String day = dayFor(entry);
                List<DownloadHistoryEntry> dailyEntries = history.getOrDefault(day, new ArrayList<>());
                dailyEntries.add(entry);
                writeDailyHistory(historyDirectory, day, dailyEntries);
                LOGGER.info("Replaced history entries for video {} with its latest download", entry.videoId());
                return null;
            });
        } catch (IOException e) {
            LOGGER.error("Failed to replace download history for video '{}'", entry.videoId(), e);
            throw new YoutubeDownloadException("No se pudo actualizar el historial de descargas JSON.", e);
        }
    }

    @Override
    public List<DownloadHistoryEntry> findAll() {
        Path historyDirectory = historyDirectory();
        if (!Files.exists(historyDirectory)) {
            return List.of();
        }
        try {
            return withHistoryLock(historyDirectory, () -> {
                migrateLegacyHistory(historyDirectory);
                List<DownloadHistoryEntry> entries = readDailyHistory(historyDirectory).values().stream()
                    .flatMap(List::stream)
                    .sorted(Comparator.comparing(DownloadHistoryEntry::downloadedAt).reversed())
                    .toList();
                LOGGER.debug("Loaded {} download history entr(y/ies)", entries.size());
                return entries;
            });
        } catch (IOException e) {
            LOGGER.error("Failed to read download history from {}", historyDirectory, e);
            throw new YoutubeDownloadException("No se pudo leer el historial de descargas.", e);
        }
    }

    private Map<String, List<DownloadHistoryEntry>> readDailyHistory(Path historyDirectory) throws IOException {
        Map<String, List<DownloadHistoryEntry>> history = new TreeMap<>();
        for (Path dailyFile : dailyFiles(historyDirectory)) {
            for (DownloadHistoryEntry entry : objectMapper.readValue(dailyFile.toFile(), DAILY_HISTORY_TYPE)) {
                history.computeIfAbsent(dayFor(entry), ignored -> new ArrayList<>()).add(entry);
            }
        }
        return history;
    }

    private void migrateLegacyHistory(Path historyDirectory) throws IOException {
        Path legacyAggregateFile = historyDirectory.resolve(HISTORY_FILENAME);
        List<Path> legacyEntryFiles = legacyFiles(historyDirectory);
        if (!Files.exists(legacyAggregateFile) && legacyEntryFiles.isEmpty()) {
            return;
        }

        Map<String, List<DownloadHistoryEntry>> history = readDailyHistory(historyDirectory);
        if (Files.exists(legacyAggregateFile)) {
            Map<String, List<DownloadHistoryEntry>> oldHistory =
                objectMapper.readValue(legacyAggregateFile.toFile(), LEGACY_HISTORY_TYPE);
            oldHistory.values().stream().flatMap(List::stream).forEach(entry -> addIfMissing(history, entry));
        }
        for (Path legacyEntryFile : legacyEntryFiles) {
            DownloadHistoryEntry entry = objectMapper.readValue(legacyEntryFile.toFile(), DownloadHistoryEntry.class);
            addIfMissing(history, entry);
        }
        for (Map.Entry<String, List<DownloadHistoryEntry>> day : history.entrySet()) {
            writeDailyHistory(historyDirectory, day.getKey(), day.getValue());
        }

        Files.deleteIfExists(legacyAggregateFile);
        for (Path legacyEntryFile : legacyEntryFiles) {
            Files.deleteIfExists(legacyEntryFile);
        }
        LOGGER.info("Migrated legacy download history into per-day JSON files in {}", historyDirectory);
    }

    private void addIfMissing(Map<String, List<DownloadHistoryEntry>> history, DownloadHistoryEntry entry) {
        List<DownloadHistoryEntry> dailyEntries =
            history.computeIfAbsent(dayFor(entry), ignored -> new ArrayList<>());
        if (!dailyEntries.contains(entry)) {
            dailyEntries.add(entry);
        }
    }

    private List<Path> dailyFiles(Path historyDirectory) throws IOException {
        try (Stream<Path> files = Files.list(historyDirectory)) {
            return files
                .filter(Files::isRegularFile)
                .filter(path -> DAILY_HISTORY_FILENAME.matcher(path.getFileName().toString()).matches())
                .sorted()
                .toList();
        }
    }

    private List<Path> legacyFiles(Path historyDirectory) throws IOException {
        try (Stream<Path> files = Files.list(historyDirectory)) {
            return files
                .filter(Files::isRegularFile)
                .filter(path -> !path.getFileName().toString().equals(HISTORY_FILENAME))
                .filter(path -> !path.getFileName().toString().equals(INACCESSIBLE_METADATA_FILENAME))
                .filter(path -> !DAILY_HISTORY_FILENAME.matcher(path.getFileName().toString()).matches())
                .filter(path -> LEGACY_HISTORY_FILENAME.matcher(path.getFileName().toString()).matches())
                .sorted()
                .toList();
        }
    }

    private void writeDailyHistory(Path historyDirectory, String day, List<DownloadHistoryEntry> entries) throws IOException {
        Path historyFile = dailyHistoryFile(historyDirectory, day);
        Path temporaryFile = Files.createTempFile(historyDirectory, ".download-history-", ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporaryFile.toFile(), entries);
            try (FileChannel fileChannel = FileChannel.open(temporaryFile, StandardOpenOption.WRITE)) {
                fileChannel.force(true);
            }
            try {
                Files.move(temporaryFile, historyFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                LOGGER.debug("Atomic move is unavailable; replacing {} directly", historyFile);
                Files.move(temporaryFile, historyFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException cleanupException) {
                e.addSuppressed(cleanupException);
            }
            throw e;
        }
    }

    private Path historyDirectory() {
        return properties.resolvedStorageDirectory().resolve("history");
    }

    private Path dailyHistoryFile(Path historyDirectory, String day) {
        return historyDirectory.resolve(day + ".json");
    }

    private String dayFor(DownloadHistoryEntry entry) {
        return Instant.parse(entry.downloadedAt()).atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    private <T> T withHistoryLock(Path historyDirectory, IoOperation<T> operation) throws IOException {
        Path lockPath = historyDirectory.resolve(".download-history.lock").toAbsolutePath().normalize();
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        jvmLock.lock();
        try (FileChannel lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = lockChannel.lock()) {
            return operation.run();
        } finally {
            jvmLock.unlock();
        }
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws IOException;
    }
}
