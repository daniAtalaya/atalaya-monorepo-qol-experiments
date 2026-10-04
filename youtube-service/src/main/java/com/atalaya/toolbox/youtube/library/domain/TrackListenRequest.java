package com.atalaya.toolbox.youtube.library.domain;

import jakarta.validation.constraints.NotBlank;

public record TrackListenRequest(@NotBlank String path) {}
