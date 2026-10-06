package com.atalaya.toolbox.preferences;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class PreferencesServiceApplication {
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    static void main(String[] args) {
        SpringApplication.run(PreferencesServiceApplication.class, args);
    }
}
