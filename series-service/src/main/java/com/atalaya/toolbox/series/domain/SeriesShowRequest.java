package com.atalaya.toolbox.series.domain;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.util.List;

public record SeriesShowRequest(
    @NotBlank @Size(max = 120) String title,
    @Size(max = 4000) String description,
    @NotBlank String status,
    @Size(max = 120) String platform,
    @Size(max = 20) List<@Size(max = 32) String> genres,
    @NotBlank String scheduleDay,
    @Size(max = 10) String scheduleTime,
    @Size(max = 64) String timezone,
    @Min(0) @Max(200) int seasons,
    @Min(0) @Max(1000) int episodesPerSeason,
    String nextEpisodeDate,
    @DecimalMin("0.0") @DecimalMax("10.0") Double rating,
    String startDate,
    String finishDate,
    @Size(max = 10000) String notes
) {
    public SeriesShowRequest {
        genres = genres == null ? List.of() : List.copyOf(genres);
    }
}
