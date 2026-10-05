package com.atalaya.toolbox.youtube.library.adapter;

import com.atalaya.toolbox.youtube.library.repository.MusicLibraryRepository;
import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import com.atalaya.toolbox.youtube.library.domain.MusicLibraryNode;
import com.atalaya.toolbox.youtube.download.domain.YoutubeDownloadException;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Repository
public class FilesystemMusicLibraryAdapter implements MusicLibraryRepository {
    private static final int MAX_NESTED_FOLDER_DEPTH = 32;

    private final YoutubeProperties properties;

    public FilesystemMusicLibraryAdapter(YoutubeProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<MusicLibraryNode> findTree(String relativePath, Integer nestedFolderDepth) {
        if (nestedFolderDepth != null && (nestedFolderDepth < 0 || nestedFolderDepth > MAX_NESTED_FOLDER_DEPTH)) {
            throw new IllegalArgumentException("La profundidad debe estar entre 0 y " + MAX_NESTED_FOLDER_DEPTH + ".");
        }
        Path musicRoot = properties.resolvedMusicDirectory(null).toAbsolutePath().normalize();
        Path musicDirectory = resolveDirectory(musicRoot, relativePath).orElse(null);
        if (musicDirectory == null || !Files.isDirectory(musicDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        int maxWalkDepth = nestedFolderDepth == null ? Integer.MAX_VALUE : nestedFolderDepth + 1;
        MutableDirectory root = new MutableDirectory("", toPortablePath(musicRoot.relativize(musicDirectory)));
        try (Stream<Path> paths = Files.walk(musicDirectory, maxWalkDepth)) {
            paths.forEach(path -> {
                if (nestedFolderDepth != null && !path.equals(musicDirectory) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    addDirectory(root, musicRoot, path);
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && isMp3(path)) {
                    addTrack(root, musicRoot, path);
                }
            });
        } catch (IOException e) {
            throw new YoutubeDownloadException("No se pudo leer la biblioteca de música.", e);
        }
        return root.children.values().stream().map(MutableNode::toNode).toList();
    }

    private Optional<Path> resolveDirectory(Path musicRoot, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return Optional.of(musicRoot);
        }
        Path requestedPath;
        try {
            requestedPath = Path.of(relativePath.replace('/', java.io.File.separatorChar));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (requestedPath.isAbsolute()) {
            return Optional.empty();
        }
        Path directory = musicRoot.resolve(requestedPath).normalize();
        if (!directory.startsWith(musicRoot) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || containsSymbolicLink(musicRoot, directory)) {
            return Optional.empty();
        }
        return Optional.of(directory);
    }

    @Override
    public Optional<Path> findTrack(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return Optional.empty();
        }
        Path musicDirectory = properties.resolvedMusicDirectory(null).toAbsolutePath().normalize();
        if (!Files.isDirectory(musicDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        Path requestedPath;
        try {
            requestedPath = Path.of(relativePath.replace('/', java.io.File.separatorChar));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (requestedPath.isAbsolute()) {
            return Optional.empty();
        }
        Path trackPath = musicDirectory.resolve(requestedPath).normalize();
        if (!trackPath.startsWith(musicDirectory) || !Files.isRegularFile(trackPath, LinkOption.NOFOLLOW_LINKS) || !isMp3(trackPath) || containsSymbolicLink(musicDirectory, trackPath)) {
            return Optional.empty();
        }
        return Optional.of(trackPath);
    }

    private void addTrack(MutableDirectory root, Path musicRoot, Path trackPath) {
        MutableDirectory current = directoryFor(root, musicRoot, trackPath.getParent());
        Path relativePath = musicRoot.relativize(trackPath);
        String filename = relativePath.getFileName().toString();
        String portablePath = toPortablePath(relativePath);
        try {
            current.children.put(filename, new MutableTrack(filename, portablePath, Files.size(trackPath)));
        } catch (IOException e) {
            throw new YoutubeDownloadException("No se pudo leer el archivo de música " + portablePath + ".", e);
        }
    }

    private void addDirectory(MutableDirectory root, Path musicRoot, Path directoryPath) {
        directoryFor(root, musicRoot, directoryPath);
    }

    private MutableDirectory directoryFor(MutableDirectory root, Path musicRoot, Path directoryPath) {
        Path relativePath = musicRoot.relativize(directoryPath);
        MutableDirectory current = root;
        Path parent = root.path.isEmpty() ? Path.of("") : Path.of(root.path);
        int rootSegmentCount = root.path.isEmpty() ? 0 : Path.of(root.path).getNameCount();
        for (int index = rootSegmentCount; index < relativePath.getNameCount(); index++) {
            Path segment = relativePath.getName(index);
            parent = parent.resolve(segment);
            String normalizedParent = toPortablePath(parent);
            MutableNode child = current.children.computeIfAbsent(segment.toString(), ignored -> new MutableDirectory(segment.toString(), normalizedParent));
            if (!(child instanceof MutableDirectory directory)) {
                throw new IllegalStateException("A music track conflicts with a directory in the music library.");
            }
            current = directory;
        }
        return current;
    }

    private boolean containsSymbolicLink(Path root, Path target) {
        Path current = root;
        for (Path segment : root.relativize(target)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMp3(Path path) {
        return path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".mp3");
    }

    private static String toPortablePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private sealed interface MutableNode permits MutableDirectory, MutableTrack {
        MusicLibraryNode toNode();
    }

    private static final class MutableDirectory implements MutableNode {
        private final String name;
        private final String path;
        private final Map<String, MutableNode> children = new LinkedHashMap<>();

        private MutableDirectory(String name, String path) {
            this.name = name;
            this.path = path;
        }

        @Override
        public MusicLibraryNode toNode() {
            return new MusicLibraryNode(name, path, true, 0, new ArrayList<>(children.values()).stream().map(MutableNode::toNode).toList());
        }
    }

    private record MutableTrack(String name, String path, long sizeBytes) implements MutableNode {
        @Override
        public MusicLibraryNode toNode() {
            return new MusicLibraryNode(name, path, false, sizeBytes, List.of());
        }
    }
}