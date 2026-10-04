package com.atalaya.toolbox.youtube.history.service;

import com.atalaya.toolbox.youtube.history.domain.InaccessibleVideoMetadata;
import com.atalaya.toolbox.youtube.history.repository.InaccessibleVideoMetadataRepository;
import com.atalaya.toolbox.youtube.history.usecase.GetInaccessibleVideoMetadataUseCase;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GetInaccessibleVideoMetadataService implements GetInaccessibleVideoMetadataUseCase {
    private final InaccessibleVideoMetadataRepository repository;

    public GetInaccessibleVideoMetadataService(InaccessibleVideoMetadataRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<InaccessibleVideoMetadata> getAll() {
        return repository.findAll();
    }

    @Override
    public boolean delete(String videoId) {
        if (videoId == null || videoId.isBlank()) {
            throw new IllegalArgumentException("El identificador del vídeo es obligatorio.");
        }
        return repository.delete(videoId);
    }

    @Override
    public void clear() {
        repository.clear();
    }
}