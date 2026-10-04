package com.atalaya.toolbox.youtube.playlist.domain;

import java.util.List;

public record PlaylistProcessingAcceptedResponse(boolean accepted, List<String> playlistIds) {}