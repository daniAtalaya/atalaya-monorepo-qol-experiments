package com.atalaya.toolbox.youtube.library;

import com.atalaya.toolbox.youtube.library.service.PlaybackPolicyService;
import com.atalaya.toolbox.youtube.library.usecase.GetMusicLibraryUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PlaybackPolicyTest {
    private final PlaybackPolicyService policy = new PlaybackPolicyService();

    @Test
    void repeatOnceIsConsumedAndThenAdvances() {
        var firstEnd = policy.onTrackEnded("once");
        assertTrue(firstEnd.replay());
        assertEquals("off", firstEnd.repeatMode());
        assertFalse(policy.onTrackEnded(firstEnd.repeatMode()).replay());
    }

    @Test
    void infiniteRepeatKeepsReplaying() {
        String mode = "infinite";
        for (int playback = 0; playback < 10; playback++) {
            var transition = policy.onTrackEnded(mode);
            assertTrue(transition.replay());
            mode = transition.repeatMode();
        }
        assertEquals("infinite", mode);
        assertThrows(IllegalArgumentException.class, () -> policy.onTrackEnded("all"));
        assertThrows(IllegalArgumentException.class, () -> policy.onTrackEnded(null));
    }

    @Test
    void apiReturnsTransitionAndRejectsUnknownAndMissingModes() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new MusicLibraryController(mock(GetMusicLibraryUseCase.class), policy)).build();
        mvc.perform(post("/api/youtube/music/playback/ended").contentType(MediaType.APPLICATION_JSON).content("{\"repeatMode\":\"once\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.replay").value(true)).andExpect(jsonPath("$.repeatMode").value("off"));
        mvc.perform(post("/api/youtube/music/playback/ended").contentType(MediaType.APPLICATION_JSON).content("{\"repeatMode\":\"all\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/youtube/music/playback/ended").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
    }
}
