package com.atalaya.toolbox.series.domain;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;

public record EpisodeWatchRequest(
    @Min(0) @Max(200) int season,
    @Min(1) @Max(1000) int episode,
    @Size(max = 160) String title,
    boolean watched,
    @Size(max = 500) String notes,
    @Min(0) @Max(200) Integer previousSeason,
    @Min(1) @Max(1000) Integer previousEpisode
) {
    public EpisodeWatchRequest(int season, int episode, String title, boolean watched, String notes) {
        this(season, episode, title, watched, notes, null, null);
    }
}
