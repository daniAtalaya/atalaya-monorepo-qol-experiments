package com.atalaya.toolbox.youtube.playlist.scheduler;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobExecutionException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Component
public class SpringPreparedPlaylistJobScheduler implements PreparedPlaylistJobScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpringPreparedPlaylistJobScheduler.class);
    private final Set<String> activePlaylistIds = ConcurrentHashMap.newKeySet();
    private final JobOperator jobOperator;
    private final Job preparedPlaylistDownloadJob;
    private final Executor executor;

    public SpringPreparedPlaylistJobScheduler(
        JobOperator jobOperator,
        Job preparedPlaylistDownloadJob,
        @Qualifier("youtubePlaylistJobExecutor") Executor executor
    ) {
        this.jobOperator = jobOperator;
        this.preparedPlaylistDownloadJob = preparedPlaylistDownloadJob;
        this.executor = executor;
    }

    @Override
    public void schedule(String playlistId) {
        if (!activePlaylistIds.add(playlistId)) {
            LOGGER.info("Prepared playlist {} is already queued or running", playlistId);
            return;
        }
        try {
            executor.execute(() -> runAsync(playlistId));
        } catch (RuntimeException e) {
            activePlaylistIds.remove(playlistId);
            throw e;
        }
    }

    private void runAsync(String playlistId) {
        try {
            LOGGER.info("Accepted asynchronous processing job for prepared playlist {}", playlistId);
            JobParameters parameters = new JobParametersBuilder()
                .addString("playlistId", playlistId)
                .addLong("requestedAt", System.currentTimeMillis())
                .toJobParameters();
            JobExecution execution = jobOperator.start(preparedPlaylistDownloadJob, parameters);
            LOGGER.info("Spring Batch job for playlist {} finished with status {}", playlistId, execution.getStatus());
        } catch (JobExecutionException | RuntimeException e) {
            LOGGER.error("Asynchronous job for prepared playlist {} failed; pending metadata is retained",
                    playlistId, e);
        } finally {
            activePlaylistIds.remove(playlistId);
        }
    }
}