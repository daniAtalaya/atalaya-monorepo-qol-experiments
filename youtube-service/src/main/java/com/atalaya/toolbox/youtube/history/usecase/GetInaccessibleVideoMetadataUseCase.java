package com.atalaya.toolbox.youtube.history.usecase;

import com.atalaya.toolbox.youtube.history.domain.InaccessibleVideoMetadata;

import java.util.List;

public interface GetInaccessibleVideoMetadataUseCase {
    List<InaccessibleVideoMetadata> getAll();
    boolean delete(String videoId);
    void clear();
}