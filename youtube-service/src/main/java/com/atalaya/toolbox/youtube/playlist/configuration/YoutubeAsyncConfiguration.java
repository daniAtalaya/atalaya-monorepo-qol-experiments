package com.atalaya.toolbox.youtube.playlist.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class YoutubeAsyncConfiguration {
    @Bean("youtubePlaylistJobExecutor")
    public Executor youtubePlaylistJobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("youtube-background-job-");
        executor.initialize();
        return executor;
    }

    @Bean("youtubePlaylistTrackExecutor")
    public AsyncTaskExecutor youtubePlaylistTrackExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("youtube-track-");
        executor.initialize();
        return executor;
    }
}