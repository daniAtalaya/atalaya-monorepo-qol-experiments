package com.atalaya.toolbox.preferences.domain;

import java.util.List;

public record PlayerThemePreference(String selectedThemeId, List<PlayerThemeOption> themes) {}
