package com.atalaya.toolbox.preferences.backup;

import com.atalaya.toolbox.preferences.PreferencesExceptionHandler;
import com.atalaya.toolbox.preferences.service.PlayerThemeService;
import com.atalaya.toolbox.preferences.service.UserPreferencesService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BackupControllerTest {
    @TempDir Path temp;

    @Test
    void exportsStreamsValidatesAndRestoresACompleteBackupWithoutASelectedProfile() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String storage = temp.resolve(".data/preferences").toString();
        var users = new UserPreferencesService(mapper, storage);
        users.loadProfiles();
        users.selectOrCreate("alice");
        users.recordListen("alice", "song.mp3");
        var themes = new PlayerThemeService(mapper, users, storage);
        var backups = new BackupService(mapper, storage, temp.resolve(".data").toString(), 1_000_000);
        try {
            var mvc = MockMvcBuilders.standaloneSetup(new BackupController(backups, users, themes))
                .setControllerAdvice(new PreferencesExceptionHandler()).build();
            var create = mvc.perform(post("/api/preferences/backups")).andExpect(status().isOk())
                .andExpect(jsonPath("$.fileCount").value(1)).andReturn();
            String id = mapper.readTree(create.getResponse().getContentAsString()).get("id").asText();
            var downloading = mvc.perform(get("/api/preferences/backups/" + id))
                .andExpect(request().asyncStarted()).andReturn();
            byte[] archive = mvc.perform(asyncDispatch(downloading)).andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
            users.recordListen("alice", "song.mp3");
            var uploaded = mvc.perform(multipart("/api/preferences/backups/restore/preview")
                    .file(new MockMultipartFile("file", "backup.zip", "application/zip", archive)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fileCount").value(1)).andReturn();
            String restoreId = mapper.readTree(uploaded.getResponse().getContentAsString()).get("id").asText();
            mvc.perform(post("/api/preferences/backups/restore/" + restoreId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.restartServices").value(true)).andExpect(jsonPath("$.recoveryDirectory").isNotEmpty());
            assertEquals(1, users.listens("alice").getFirst().listenCount());
            mvc.perform(post("/api/preferences/backups/restore/" + restoreId)).andExpect(status().isBadRequest());
            mvc.perform(multipart("/api/preferences/backups/restore/preview")
                .file(new MockMultipartFile("file", "broken.zip", "application/zip", new byte[] {1, 2, 3})))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isNotEmpty());
            assertEquals(1, users.listens("alice").getFirst().listenCount());
            assertTrue(Files.isRegularFile(temp.resolve(".data/preferences/users.json")));
        } finally {
            backups.close();
        }
    }
}
