package com.atalaya.toolbox.youtube.users.domain;

import jakarta.validation.constraints.NotBlank;

public record MusicPlayerUserRequest(@NotBlank String username) {}
