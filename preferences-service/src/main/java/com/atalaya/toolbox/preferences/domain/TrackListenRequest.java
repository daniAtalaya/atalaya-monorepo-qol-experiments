package com.atalaya.toolbox.preferences.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TrackListenRequest(@NotBlank @Size(max = 1024) String path) {}
