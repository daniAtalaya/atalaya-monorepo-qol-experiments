package com.atalaya.toolbox.preferences;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "toolbox.preferences.storage-directory=target/test-preferences-data",
    "toolbox.preferences.legacy-user-files="
})
class PreferencesControllerTest {
    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void managesProfilesAndSettingsWithoutYouTube() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        String username = "profile-" + UUID.randomUUID().toString().substring(0, 8);

        mockMvc.perform(post("/api/preferences/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("username", username))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value(username));

        mockMvc.perform(get("/api/preferences/users"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.username == '" + username + "')]").exists());

        mockMvc.perform(put("/api/preferences/player-settings")
                .header("X-Atalaya-Username", username)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"volume":0.35}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.volume").value(0.35));
        mockMvc.perform(get("/api/preferences/player-settings").header("X-Atalaya-Username", username))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.volume").value(0.35));
    }
}
