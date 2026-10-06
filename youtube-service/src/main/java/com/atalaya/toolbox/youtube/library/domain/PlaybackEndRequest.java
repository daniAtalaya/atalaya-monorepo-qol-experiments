package com.atalaya.toolbox.youtube.library.domain;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PlaybackEndRequest(
    @NotNull @Pattern(regexp = "off|once|infinite") String repeatMode
) {}
