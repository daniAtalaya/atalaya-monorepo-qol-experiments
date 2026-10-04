package com.atalaya.toolbox.youtube.library.usecase;

import com.atalaya.toolbox.youtube.library.domain.MusicLibraryNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface GetMusicLibraryUseCase {
    List<MusicLibraryNode> getTree(String relativePath, Integer nestedFolderDepth);

    Optional<Path> getTrack(String relativePath);
}
