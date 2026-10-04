package com.atalaya.toolbox.youtube.themes.domain;

import java.util.List;

public record PlayerThemePreference(String selectedThemeId, List<PlayerThemeOption> themes) {}
