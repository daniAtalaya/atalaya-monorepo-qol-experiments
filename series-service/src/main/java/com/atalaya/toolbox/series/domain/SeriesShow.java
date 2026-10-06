package com.atalaya.toolbox.series.domain;

import java.util.List;

public record SeriesShow(
    String id,
    String title,
    String description,
    String status,
    String platform,
    List<String> genres,
    String scheduleDay,
    String scheduleTime,
    String timezone,
    int seasons,
    int episodesPerSeason,
    List<WatchedEpisode> watchedEpisodes,
    String nextEpisodeDate,
    Double rating,
    String startDate,
    String finishDate,
    String coverUrl,
    String notes,
    String createdAt,
    String updatedAt
) {}
