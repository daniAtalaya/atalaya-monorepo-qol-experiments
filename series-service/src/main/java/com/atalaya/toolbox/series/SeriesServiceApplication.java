package com.atalaya.toolbox.series;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SeriesServiceApplication {
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    static void main(String[] args) {
        SpringApplication.run(SeriesServiceApplication.class, args);
    }
}
