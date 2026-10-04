package com.atalaya.toolbox.youtube.download.domain;

import jakarta.validation.constraints.NotBlank;

public record YoutubeDownloadRequest(@NotBlank String url, String destination) {}