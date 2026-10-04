package com.atalaya.toolbox.youtube.users.domain;

import com.atalaya.toolbox.youtube.library.domain.MostListenedTrack;

import java.util.List;

public record MusicPlayerUserProfile(String username, String selectedThemeId, List<MostListenedTrack> listens) {}
