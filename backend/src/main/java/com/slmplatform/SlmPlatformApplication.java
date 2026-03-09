package com.slmplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@SpringBootApplication
@EnableMongoRepositories(basePackages = "com.slmplatform")
public class SlmPlatformApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(SlmPlatformApplication.class, args);
    }

    // Explicitly declare the ObjectMapper Bean so ToolOrchestrator can find it
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}