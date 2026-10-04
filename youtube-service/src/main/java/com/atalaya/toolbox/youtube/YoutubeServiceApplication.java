package com.atalaya.toolbox.youtube;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class YoutubeServiceApplication {
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    static void main(String[] args) {
        SpringApplication.run(YoutubeServiceApplication.class, args);
    }
}