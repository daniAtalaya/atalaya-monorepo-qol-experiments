package com.atalaya.toolbox.youtube.playlist.usecase;

import java.util.List;

public interface SchedulePreparedPlaylistProcessingUseCase {
    List<String> schedule(String playlistId);
}