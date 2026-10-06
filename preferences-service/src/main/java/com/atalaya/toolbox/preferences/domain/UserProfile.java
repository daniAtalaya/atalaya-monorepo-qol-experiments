package com.atalaya.toolbox.preferences.domain;

import java.util.List;

public record UserProfile(String username, String selectedThemeId, List<MostListenedTrack> listens, Double volume) {}
