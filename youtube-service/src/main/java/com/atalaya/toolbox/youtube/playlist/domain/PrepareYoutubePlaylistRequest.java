package com.atalaya.toolbox.youtube.playlist.domain;

import jakarta.validation.constraints.NotBlank;

public record PrepareYoutubePlaylistRequest(@NotBlank String url, String destination) {}
