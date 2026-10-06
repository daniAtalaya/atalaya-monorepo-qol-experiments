package com.atalaya.toolbox.preferences.domain;

import jakarta.validation.constraints.NotBlank;

public record UserProfileRequest(@NotBlank String username) {}
