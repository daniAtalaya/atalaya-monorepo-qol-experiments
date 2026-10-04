package com.atalaya.toolbox.youtube.library.repository;

import com.atalaya.toolbox.youtube.library.domain.MusicLibraryNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface MusicLibraryRepository {
    List<MusicLibraryNode> findTree(String relativePath, Integer nestedFolderDepth);

    Optional<Path> findTrack(String relativePath);
}
