package com.atalaya.toolbox.series;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "toolbox.series.storage-directory=target/test-series-data")
class SeriesTrackerControllerTest {
    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
    }

    @Test
    void recordsEpisodesForTheSharedUsernameOverHttp() throws Exception {
        String username = "controller-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(get("/api/series/shows").header("X-Atalaya-Username", username))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        String showRequest = """
            {
              "title": "Lanterns at Sea",
              "description": "A story under the stars.",
              "status": "AIRING",
              "platform": "Streambox",
              "genres": ["Drama", "Fantasy"],
              "scheduleDay": "FRIDAY",
              "scheduleTime": "21:00",
              "timezone": "Europe/Madrid",
              "seasons": 2,
              "episodesPerSeason": 10,
              "nextEpisodeDate": "2026-10-09",
              "rating": 9.0,
              "startDate": "2026-09-01",
              "finishDate": null,
              "notes": "Watch with tea."
            }
            """;
        MvcResult created = mockMvc.perform(post("/api/series/shows")
                .header("X-Atalaya-Username", username)
                .contentType(MediaType.APPLICATION_JSON)
                .content(showRequest))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Lanterns at Sea"))
            .andReturn();
        String showId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(put("/api/series/shows/{id}/episodes", showId)
                .header("X-Atalaya-Username", username)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"season":1,"episode":2,"title":"The tide turns","watched":true,"notes":"A tiny detail."}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.watchedEpisodes[0].episode").value(2));

        mockMvc.perform(put("/api/series/shows/{id}/episodes", showId)
                .header("X-Atalaya-Username", username)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"season":1,"episode":3,"title":"A revised title","watched":true,
                     "notes":"Updated memory.","previousSeason":1,"previousEpisode":2}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.watchedEpisodes[0].episode").value(3))
            .andExpect(jsonPath("$.watchedEpisodes[0].title").value("A revised title"));

        mockMvc.perform(get("/api/series/shows").header("X-Atalaya-Username", username))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].watchedEpisodes.length()").value(1));
    }
}
