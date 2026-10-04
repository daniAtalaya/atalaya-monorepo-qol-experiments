package com.atalaya.toolbox.youtube.history.domain;

import java.util.List;

public record InaccessibleVideoMetadata(String videoId, String firstSeenAt, String lastSeenAt, int observations, List<String> sourceUrls, String latestReason) {}