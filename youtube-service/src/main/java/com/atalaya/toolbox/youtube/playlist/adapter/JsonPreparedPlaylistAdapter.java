package com.atalaya.toolbox.youtube.playlist.adapter;

import com.atalaya.toolbox.youtube.playlist.repository.PreparedPlaylistRepository;
import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import com.atalaya.toolbox.youtube.playlist.domain.PreparedPlaylist;
import com.atalaya.toolbox.youtube.playlist.domain.PreparedTrack;
import com.atalaya.toolbox.youtube.download.domain.YoutubeDownloadException;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

@Repository
public class JsonPreparedPlaylistAdapter implements PreparedPlaylistRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(JsonPreparedPlaylistAdapter.class);
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final YoutubeProperties properties;
    private final ObjectMapper objectMapper;

    public JsonPreparedPlaylistAdapter(YoutubeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<PreparedPlaylist> findById(String id) {
        preparedDirectory();
        Path file = playlistFile(id);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(withLock(id, () -> readPlaylist(file)));
        } catch (IOException e) {
            throw new YoutubeDownloadException("No se pudo leer la playlist preparada " + id + ".", e);
        }
    }

    @Override
    public List<PreparedPlaylist> findAll() {
        Path directory = preparedDirectory();
        if (!Files.exists(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().map(this::readPlaylist).toList();
        } catch (IOException e) {
            throw new YoutubeDownloadException("No se pudieron leer las playlists preparadas.", e);
        }
    }

    @Override
    public List<String> findPendingIds() {
        return findAll().stream().filter(playlist -> !playlist.tracks().isEmpty()).map(PreparedPlaylist::id).toList();
    }

    @Override
    public void save(PreparedPlaylist playlist) {
        try {
            Files.createDirectories(preparedDirectory());
            withLock(playlist.id(), () -> {
                writePlaylist(playlist);
                LOGGER.info("Saved prepared playlist {} with {} pending track(s)", playlist.id(), playlist.tracks().size());
                return null;
            });
        } catch (IOException e) {
            throw new YoutubeDownloadException("No se pudo guardar la playlist preparada.", e);
        }
    }

    @Override
    public void removeTrack(String playlistId, String videoId) {
        try {
            withLock(playlistId, () -> {
                Path file = playlistFile(playlistId);
                if (!Files.exists(file)) {
                    return null;
                }
                PreparedPlaylist current = readPlaylist(file);
                List<PreparedTrack> remaining = current.tracks().stream().filter(track -> !track.videoId().equals(videoId)).toList();
                if (remaining.size() != current.tracks().size()) {
                    writePlaylist(new PreparedPlaylist(current.id(), current.requestedUrl(), current.preparedAt(), current.totalTrackCount(), remaining, current.destination()));
                    LOGGER.info("Removed successfully downloaded track {} from prepared playlist {}", videoId, playlistId);
                }
                return null;
            });
        } catch (IOException e) {
            throw new YoutubeDownloadException("No se pudo actualizar la playlist preparada " + playlistId + ".", e);
        }
    }

    private Path preparedDirectory() {
        return properties.resolvedStorageDirectory().resolve("prepared-playlists");
    }

    private Path playlistFile(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("El identificador de playlist preparada no es válido.");
        }
        return preparedDirectory().resolve(id + ".json");
    }

    @Override
    public Path filePath(String playlistId) {
        return playlistFile(playlistId);
    }

    private PreparedPlaylist readPlaylist(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), PreparedPlaylist.class);
        } catch (IOException e) {
            throw new YoutubeDownloadException("No se pudo interpretar el archivo preparado " + file.getFileName(), e);
        }
    }

    private void writePlaylist(PreparedPlaylist playlist) throws IOException {
        Path target = playlistFile(playlist.id());
        Path temporaryFile = Files.createTempFile(preparedDirectory(), ".playlist-", ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporaryFile.toFile(), playlist);
            try (FileChannel channel = FileChannel.open(temporaryFile, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporaryFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryFile, target, StandardCopyOption.REPLACE_EXISTING);
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

    private <T> T withLock(String id, IoOperation<T> operation) throws IOException {
        Path lockPath = preparedDirectory().resolve(id + ".lock").toAbsolutePath().normalize();
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        jvmLock.lock();
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
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