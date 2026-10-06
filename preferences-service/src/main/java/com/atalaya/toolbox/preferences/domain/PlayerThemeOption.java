package com.atalaya.toolbox.preferences.domain;

import java.util.List;

public record PlayerThemeOption(
    String id,
    String name,
    String description,
    String mode,
    String background,
    String surface,
    String text,
    String muted,
    String accent,
    String line,
    String backdrop,
    List<String> visualizerPalette,
    String visualizerStyle,
    int visualizerBarCount,
    double visualizerSensitivity
) {}
