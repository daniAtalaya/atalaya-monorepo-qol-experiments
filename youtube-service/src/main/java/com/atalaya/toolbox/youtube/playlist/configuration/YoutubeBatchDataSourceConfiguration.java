package com.atalaya.toolbox.youtube.playlist.configuration;

import com.atalaya.toolbox.youtube.settings.domain.YoutubeProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class YoutubeBatchDataSourceConfiguration {

    @Bean
    @DependsOn("youtubeSettingsService")
    public DataSource batchDataSource(YoutubeProperties properties) throws IOException {
        Path storageDirectory = properties.resolvedStorageDirectory();
        Files.createDirectories(storageDirectory);
        String databasePath = storageDirectory.resolve("batch-metadata")
            .toAbsolutePath()
            .normalize()
            .toString()
            .replace('\\', '/');
        return DataSourceBuilder.create()
            .driverClassName("org.h2.Driver")
            .url("jdbc:h2:file:" + databasePath + ";DB_CLOSE_ON_EXIT=FALSE")
            .username("sa")
            .build();
    }
}
