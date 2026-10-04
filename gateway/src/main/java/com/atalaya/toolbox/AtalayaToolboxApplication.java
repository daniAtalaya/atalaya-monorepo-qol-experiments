package com.atalaya.toolbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AtalayaToolboxApplication {
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    static void main(String[] args) {
        SpringApplication.run(AtalayaToolboxApplication.class, args);
    }
}