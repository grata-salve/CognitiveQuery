package com.example.cognitivequery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CognitiveQueryApplication {

    public static void main(String[] args) {
        SpringApplication.run(CognitiveQueryApplication.class, args);
    }

    // Make sure the CommandLineRunner for project analysis is removed or made conditional,
    // so it doesn't run automatically on every startup.
    // @Bean
    // public CommandLineRunner run(ProjectAnalyzerService analyzer) { ... }

}