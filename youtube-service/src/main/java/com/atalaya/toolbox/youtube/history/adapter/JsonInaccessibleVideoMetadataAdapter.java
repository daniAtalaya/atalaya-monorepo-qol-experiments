package com.atalaya.toolbox.youtube.history.adapter;

import com.atalaya.toolbox.youtube.history.repository.InaccessibleVideoMetadataRepository;
import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import com.atalaya.toolbox.youtube.history.domain.InaccessibleVideoMetadata;
import com.atalaya.toolbox.youtube.download.domain.YoutubeDownloadException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class JsonInaccessibleVideoMetadataAdapter implements InaccessibleVideoMetadataRepository {
    private static final String FILENAME = "inaccessible-video-metadata.json";
    private static final TypeReference<List<InaccessibleVideoMetadata>> DATA_TYPE = new TypeReference<>() {};

    private final YoutubeProperties properties;
    private final ObjectMapper objectMapper;

    public JsonInaccessibleVideoMetadataAdapter(YoutubeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized void recordFailure(String videoId, String sourceUrl, String reason) {
        Path file = metadataFile();
        try {
            Files.createDirectories(file.getParent());
            Map<String, InaccessibleVideoMetadata> entries = read(file);
            String now = Instant.now().toString();
            InaccessibleVideoMetadata previous = entries.get(videoId);
            List<String> sourceUrls = new ArrayList<>(previous == null ? List.of() : previous.sourceUrls());
            if (sourceUrl != null && !sourceUrl.isBlank() && !sourceUrls.contains(sourceUrl)) {
                sourceUrls.add(sourceUrl);
            }
            entries.put(videoId, new InaccessibleVideoMetadata(videoId, previous == null ? now : previous.firstSeenAt(), now, previous == null ? 1 : previous.observations() + 1, List.copyOf(sourceUrls), reason));
            write(file, entries.values().stream().toList());
        } catch (IOException e) {
            throw new YoutubeDownloadException("No se pudo guardar el registro de metadata inaccesible.", e);
        }
    }

    @Override
    public synchronized void recordSuccess(String videoId) {
        Path file = metadataFile();
        try {
            Map<String, InaccessibleVideoMetadata> entries = read(file);
            if (entries.remove(videoId) != null) {
                write(file, entries.values().stream().toList());
            }
        } catch (IOException e) {
            throw new YoutubeDownloadException("No se pudo actualizar el registro de metadata inaccesible.", e);
        }
    }

    @Override
    public synchronized List<InaccessibleVideoMetadata> findAll() {
        Path file = metadataFile();
        try {
            return List.copyOf(read(file).values());
        } catch (IOException e) {
            throw new YoutubeDownloadException("No se pudo leer el registro de metadata inaccesible.", e);
        }
    }

    @Override
    public synchronized boolean delete(String videoId) {
        Path file = metadataFile();
        try {
            Map<String, InaccessibleVideoMetadata> entries = read(file);
            if (!entries.containsKey(videoId)) return false;
            entries.remove(videoId);
            write(file, entries.values().stream().toList());
            return true;
        } catch (IOException e) {
            throw new YoutubeDownloadException("No se pudo eliminar la entrada de metadata inaccesible.", e);
        }
    }

    @Override
    public synchronized void clear() {
        Path file = metadataFile();
        try {
            Files.createDirectories(file.getParent());
            migrateLegacyFile(file);
            write(file, List.of());
        } catch (IOException e) {
            throw new YoutubeDownloadException("No se pudo vaciar el registro de metadata inaccesible.", e);
        }
    }

    private Map<String, InaccessibleVideoMetadata> read(Path file) throws IOException {
        Map<String, InaccessibleVideoMetadata> entries = new LinkedHashMap<>();
        migrateLegacyFile(file);
        if (Files.exists(file)) {
            for (InaccessibleVideoMetadata entry : objectMapper.readValue(file.toFile(), DATA_TYPE)) {
                entries.put(entry.videoId(), entry);
            }
        }
        return entries;
    }

    private Path metadataFile() {
        return properties.resolvedStorageDirectory().resolve("history").resolve(FILENAME);
    }

    private void migrateLegacyFile(Path target) throws IOException {
        Path workFile = properties.resolvedStorageDirectory().resolve("work").resolve(FILENAME);
        Path legacyFile = properties.resolvedStorageDirectory().resolve(FILENAME);
        Path source = Files.exists(workFile) ? workFile : legacyFile;
        if (!Files.exists(target) && Files.exists(source)) {
            Files.createDirectories(target.getParent());
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, target);
            }
        }
    }

    private void write(Path file, List<InaccessibleVideoMetadata> entries) throws IOException {
        Path temporaryFile = Files.createTempFile(file.getParent(), ".inaccessible-metadata-", ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporaryFile.toFile(), entries);
            try {
                Files.move(temporaryFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(temporaryFile);
            throw e;
        }
    }
}