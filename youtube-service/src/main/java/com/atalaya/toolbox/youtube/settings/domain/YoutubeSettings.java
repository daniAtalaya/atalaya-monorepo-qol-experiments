package com.atalaya.toolbox.youtube.settings.domain;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record YoutubeSettings(
    @NotBlank String storageDirectory,
    @NotBlank String jsRuntime,
    @NotBlank String ejsRemoteComponents,
    @Min(1) int playlistDownloadAttempts,
    @PositiveOrZero long playlistRetryDelayMillis
) {}
