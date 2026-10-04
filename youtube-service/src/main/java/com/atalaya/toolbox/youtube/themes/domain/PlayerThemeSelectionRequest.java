package com.atalaya.toolbox.youtube.themes.domain;

import jakarta.validation.constraints.NotBlank;

public record PlayerThemeSelectionRequest(@NotBlank String themeId) {}
