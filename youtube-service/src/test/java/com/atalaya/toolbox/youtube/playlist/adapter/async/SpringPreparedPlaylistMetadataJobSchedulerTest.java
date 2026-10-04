package com.atalaya.toolbox.youtube.playlist.adapter.async;

import com.atalaya.toolbox.youtube.playlist.scheduler.SpringPreparedPlaylistMetadataJobScheduler;
import com.atalaya.toolbox.youtube.playlist.usecase.PreparePlaylistMetadataUseCase;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SpringPreparedPlaylistMetadataJobSchedulerTest {

    @Test
    void acceptsMetadataPreparationBeforeRunningTheBackgroundTask() {
        PreparePlaylistMetadataUseCase prepareUseCase = mock(PreparePlaylistMetadataUseCase.class);
        Queue<Runnable> queuedTasks = new ArrayDeque<>();
        SpringPreparedPlaylistMetadataJobScheduler scheduler =
            new SpringPreparedPlaylistMetadataJobScheduler(prepareUseCase, queuedTasks::add);

        assertTrue(scheduler.schedule("PL_123", "https://youtube.com/playlist?list=PL_123", null));
        verifyNoInteractions(prepareUseCase);
        assertEquals("preparing", scheduler.status("PL_123").orElseThrow().state());

        queuedTasks.remove().run();

        verify(prepareUseCase).prepare("PL_123", "https://youtube.com/playlist?list=PL_123", null);
        assertEquals("prepared", scheduler.status("PL_123").orElseThrow().state());
    }

    @Test
    void exposesBackgroundPreparationFailuresForStatusPolling() {
        PreparePlaylistMetadataUseCase prepareUseCase = mock(PreparePlaylistMetadataUseCase.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("metadata unavailable"))
            .when(prepareUseCase).prepare("PL_123", "https://youtube.com/playlist?list=PL_123", null);
        Queue<Runnable> queuedTasks = new ArrayDeque<>();
        SpringPreparedPlaylistMetadataJobScheduler scheduler =
            new SpringPreparedPlaylistMetadataJobScheduler(prepareUseCase, queuedTasks::add);
        scheduler.schedule("PL_123", "https://youtube.com/playlist?list=PL_123", null);

        queuedTasks.remove().run();

        var status = scheduler.status("PL_123").orElseThrow();
        assertEquals("failed", status.state());
        assertEquals("metadata unavailable", status.error());
    }
}
