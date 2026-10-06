package com.atalaya.toolbox.preferences.domain;

import jakarta.validation.constraints.NotBlank;

public record PlayerThemeSelectionRequest(@NotBlank String themeId) {}
