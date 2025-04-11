package com.example.cognitivequery;

import com.example.cognitivequery.service.projectextractor.ProjectAnalyzerService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class CognitiveQueryApplication {

    public static void main(String[] args) {
        SpringApplication.run(CognitiveQueryApplication.class, args);
    }

    @Bean
    public CommandLineRunner run(ProjectAnalyzerService analyzer) {
        return args -> analyzer.processProject("https://github.com/grata-salve/tasker");
    }
}
