package com.atalaya.toolbox.preferences;

import com.atalaya.toolbox.preferences.domain.MostListenedTrack;
import com.atalaya.toolbox.preferences.domain.PlayerSettings;
import com.atalaya.toolbox.preferences.domain.PlayerSettingsRequest;
import com.atalaya.toolbox.preferences.domain.TrackListenRequest;
import com.atalaya.toolbox.preferences.domain.UserProfileRequest;
import com.atalaya.toolbox.preferences.domain.UserProfileSummary;
import com.atalaya.toolbox.preferences.service.UserPreferencesService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/preferences")
public class PreferencesController {
    private final UserPreferencesService preferences;

    public PreferencesController(UserPreferencesService preferences) {
        this.preferences = preferences;
    }

    @GetMapping("/users")
    public List<UserProfileSummary> listUsers() {
        return preferences.listUsers();
    }

    @PostMapping("/users")
    public UserProfileSummary selectOrCreate(@Valid @RequestBody UserProfileRequest request) {
        return preferences.selectOrCreate(request.username());
    }

    @GetMapping("/player-settings")
    public PlayerSettings playerSettings(@RequestHeader("X-Atalaya-Username") String username) {
        return preferences.playerSettings(username);
    }

    @PutMapping("/player-settings")
    public PlayerSettings updatePlayerSettings(
        @RequestHeader("X-Atalaya-Username") String username,
        @Valid @RequestBody PlayerSettingsRequest request
    ) {
        return preferences.updatePlayerSettings(username, request.volume());
    }

    @GetMapping("/music/most-listened")
    public List<MostListenedTrack> mostListened(@RequestHeader("X-Atalaya-Username") String username) {
        return preferences.listens(username);
    }

    @PostMapping("/music/listens")
    public MostListenedTrack recordListen(
        @RequestHeader("X-Atalaya-Username") String username,
        @Valid @RequestBody TrackListenRequest request
    ) {
        return preferences.recordListen(username, request.path());
    }
}
