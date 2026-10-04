package com.atalaya.toolbox.youtube.library.domain;

public record MostListenedTrack(String path, String name, long listenCount, String lastListenedAt) {}
