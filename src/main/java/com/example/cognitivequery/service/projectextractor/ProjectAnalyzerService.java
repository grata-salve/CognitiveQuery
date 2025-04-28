package com.example.cognitivequery.service.projectextractor;

import com.example.cognitivequery.model.ir.SchemaInfo;
import com.example.cognitivequery.service.parser.SchemaParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;


@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectAnalyzerService {
    // ... поля и конструктор ...
    private final GitCloneService gitCloneService;
    private final SchemaParserService schemaParserService;

    @Value("${app.analysis.output.base-path:/tmp/cognitivequery/processed}")
    private String processedSchemaBasePath;

    public Path analyzeAndProcessProject(String gitUrl, boolean cleanupClone) {
        Path projectPath = null;
        Path schemaFilePath = null;
        try {
            log.info("Starting analysis and schema parsing for Git URL: {}", gitUrl);
            projectPath = gitCloneService.cloneRepository(gitUrl);
            log.info("Repository cloned successfully to: {}", projectPath);

            Path sourceRoot = projectPath;
            SchemaInfo schemaInfo = schemaParserService.parseProject(sourceRoot, gitUrl);
            log.info("Project source parsed. Found {} entities.", schemaInfo.getEntities().size());

            Path baseOutputPath = Paths.get(processedSchemaBasePath);
            Files.createDirectories(baseOutputPath);

            // *** ИСПРАВЛЕНИЕ: Используем исправленный safeUrlToFilePart ***
            String safePart = safeUrlToFilePart(gitUrl); // Вызываем исправленный метод
            String schemaFileName = "schema-" + safePart + "-" + UUID.randomUUID() + ".json";
            schemaFilePath = baseOutputPath.resolve(schemaFileName);

            schemaParserService.writeSchemaToJsonFile(schemaInfo, schemaFilePath);
            log.info("Schema IR saved to: {}", schemaFilePath);

            return schemaFilePath;

        } catch (Exception e) {
            log.error("Failed to process project and generate schema from Git URL: {}", gitUrl, e);
            // ... (блок cleanup при ошибке) ...
            if (projectPath != null && Files.exists(projectPath)) { /* ... */ }
            throw new RuntimeException("Failed to process project/generate schema from URL: " + gitUrl + ". Reason: " + e.getMessage(), e);
        } finally {
            // ... (блок cleanup при успехе) ...
            if (schemaFilePath != null && cleanupClone && projectPath != null && Files.exists(projectPath)) { /* ... */ } else if (cleanupClone && projectPath != null) { /* ... */ }
        }
    }


    // *** ИСПРАВЛЕННЫЙ МЕТОД ***
    private String safeUrlToFilePart(String url) {
        String cleaned = url.replaceAll("[^a-zA-Z0-9.\\-_]", "_")
                .replaceAll("_+", "_");
        // Убедимся, что не выходим за границы строки
        int maxLength = 50;
        return cleaned.substring(0, Math.min(cleaned.length(), maxLength));
    }
}