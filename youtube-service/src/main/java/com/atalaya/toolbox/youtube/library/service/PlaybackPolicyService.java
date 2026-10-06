package com.atalaya.toolbox.youtube.library.service;

import com.atalaya.toolbox.youtube.library.domain.PlaybackTransition;
import org.springframework.stereotype.Service;

/** Only natural track endings consume repeat-once; explicit navigation bypasses this policy. */
@Service
public class PlaybackPolicyService {
    public PlaybackTransition onTrackEnded(String repeatMode) {
        if (repeatMode == null) throw new IllegalArgumentException("Repeat mode is required.");
        return switch (repeatMode) {
            case "off" -> new PlaybackTransition(false, "off");
            case "once" -> new PlaybackTransition(true, "off");
            case "infinite" -> new PlaybackTransition(true, "infinite");
            default -> throw new IllegalArgumentException("Repeat mode must be off, once, or infinite.");
        };
    }
}
