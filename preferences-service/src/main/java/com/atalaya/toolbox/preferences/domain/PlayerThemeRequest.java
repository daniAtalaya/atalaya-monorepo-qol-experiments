package com.atalaya.toolbox.preferences.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record PlayerThemeRequest(
    @NotBlank String name,
    @NotBlank String description,
    @NotBlank String mode,
    @NotBlank String background,
    @NotBlank String surface,
    @NotBlank String text,
    @NotBlank String muted,
    @NotBlank String accent,
    @NotBlank String line,
    @NotBlank String backdrop,
    @NotNull List<String> visualizerPalette,
    @NotBlank String visualizerStyle,
    int visualizerBarCount,
    double visualizerSensitivity
) {}
