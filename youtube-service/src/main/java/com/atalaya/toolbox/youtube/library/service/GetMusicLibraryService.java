package com.atalaya.toolbox.youtube.library.service;

import com.atalaya.toolbox.youtube.library.domain.MusicLibraryNode;
import com.atalaya.toolbox.youtube.library.repository.MusicLibraryRepository;
import com.atalaya.toolbox.youtube.library.usecase.GetMusicLibraryUseCase;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@Service
public class GetMusicLibraryService implements GetMusicLibraryUseCase {
    private final MusicLibraryRepository musicLibraryRepository;

    public GetMusicLibraryService(MusicLibraryRepository musicLibraryRepository) {
        this.musicLibraryRepository = musicLibraryRepository;
    }

    @Override
    public List<MusicLibraryNode> getTree(String relativePath, Integer nestedFolderDepth) {
        return musicLibraryRepository.findTree(relativePath, nestedFolderDepth);
    }

    @Override
    public Optional<Path> getTrack(String relativePath) {
        return musicLibraryRepository.findTrack(relativePath);
    }
}
