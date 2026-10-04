package com.atalaya.toolbox.youtube.history.repository;

import com.atalaya.toolbox.youtube.history.domain.InaccessibleVideoMetadata;

import java.util.List;

public interface InaccessibleVideoMetadataRepository {
    void recordFailure(String videoId, String sourceUrl, String reason);
    void recordSuccess(String videoId);
    List<InaccessibleVideoMetadata> findAll();
    boolean delete(String videoId);
    void clear();
}