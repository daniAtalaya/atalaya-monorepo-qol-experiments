package com.atalaya.toolbox.preferences.domain;

public record MostListenedTrack(String path, String name, long listenCount, String lastListenedAt) {}
