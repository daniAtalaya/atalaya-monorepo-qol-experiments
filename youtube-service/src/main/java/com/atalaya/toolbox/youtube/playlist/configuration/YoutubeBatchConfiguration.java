package com.atalaya.toolbox.youtube.playlist.configuration;

import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import com.atalaya.toolbox.youtube.playlist.batch.PreparedPlaylistTrack;
import com.atalaya.toolbox.youtube.playlist.batch.PreparedPlaylistTrackProcessor;
import com.atalaya.toolbox.youtube.playlist.batch.PreparedPlaylistTrackResult;
import com.atalaya.toolbox.youtube.playlist.batch.SynchronizedPlaylistTrackReader;
import com.atalaya.toolbox.youtube.playlist.batch.PreparedPlaylistSkipListener;
import com.atalaya.toolbox.youtube.playlist.batch.PreparedPlaylistTrackWriter;
import com.atalaya.toolbox.youtube.playlist.repository.PreparedPlaylistRepository;
import com.atalaya.toolbox.youtube.playlist.domain.PreparedPlaylist;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.Iterator;

@Configuration
public class YoutubeBatchConfiguration {
    private static final int CHUNK_SIZE = 4;

    @Bean
    public Job preparedPlaylistDownloadJob(JobRepository jobRepository, Step preparedPlaylistDownloadStep) {
        return new JobBuilder("preparedPlaylistDownloadJob", jobRepository)
            .start(preparedPlaylistDownloadStep)
            .build();
    }

    @Bean
    public Step preparedPlaylistDownloadStep(
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager,
        SynchronizedPlaylistTrackReader preparedPlaylistTrackReader,
        PreparedPlaylistTrackProcessor processor,
        PreparedPlaylistTrackWriter writer,
        PreparedPlaylistSkipListener skipListener,
        YoutubeProperties properties,
        @Qualifier("youtubePlaylistTrackExecutor")
        AsyncTaskExecutor trackExecutor
    ) {
        int maxAttempts = Math.max(1, properties.getPlaylistDownloadAttempts());
        RetryPolicy retryPolicy = RetryPolicy.builder()
            .maxRetries(maxAttempts - 1L)
            .delay(Duration.ofMillis(Math.max(0, properties.getPlaylistRetryDelayMillis())))
            .jitter(Duration.ZERO)
            .multiplier(2.0)
            .maxDelay(Duration.ofSeconds(30))
            .includes(RuntimeException.class)
            .build();
        var stepBuilder = new StepBuilder("preparedPlaylistDownloadStep", jobRepository)
            .<PreparedPlaylistTrack, PreparedPlaylistTrackResult>chunk(CHUNK_SIZE)
            .transactionManager(transactionManager)
            .reader(preparedPlaylistTrackReader)
            .processor(processor)
            .writer(writer)
            .taskExecutor(trackExecutor)
            .faultTolerant()
            .skip(RuntimeException.class)
            .skipLimit(Integer.MAX_VALUE)
            .skipListener(skipListener)
            .retryPolicy(retryPolicy);
        return stepBuilder.build();
    }

    @Bean
    @StepScope
    public SynchronizedPlaylistTrackReader preparedPlaylistTrackReader(
        PreparedPlaylistRepository playlistRepository,
        @Value("#{jobParameters['playlistId']}") String playlistId
    ) {
        PreparedPlaylist playlist = playlistRepository.findById(playlistId)
            .orElseThrow(() -> new IllegalArgumentException("No existe una playlist preparada con el identificador indicado."));
        Iterator<PreparedPlaylistTrack> iterator = playlist.tracks().stream()
            .map(track -> new PreparedPlaylistTrack(playlistId, track))
            .iterator();
        return new SynchronizedPlaylistTrackReader(iterator);
    }
}
