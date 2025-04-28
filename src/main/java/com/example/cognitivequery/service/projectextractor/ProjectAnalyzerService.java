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
    private final GitCloneService gitCloneService;
    private final SchemaParserService schemaParserService;

    @Value("${app.analysis.output.base-path:/tmp/cognitivequery/processed}")
    private String processedSchemaBasePath;

    /**
     * Clones a Git repository, parses JPA entities into a schema IR (JSON),
     * saves the IR to a file, and returns the path to that file.
     * Optionally cleans up the original clone.
     *
     * @param gitUrl The URL of the Git repository.
     * @param cleanupClone Whether to delete the original cloned repository after processing.
     * @return Path to the generated JSON schema file.
     * @throws RuntimeException if cloning, parsing, or saving fails.
     */
    public Path analyzeAndProcessProject(String gitUrl, boolean cleanupClone) {
        Path projectPath = null;
        Path schemaFilePath = null;
        try {
            log.info("Starting analysis and schema parsing for Git URL: {}", gitUrl);
            projectPath = gitCloneService.cloneRepository(gitUrl);
            log.info("Repository cloned successfully to: {}", projectPath);

            Path sourceRoot = projectPath; // Assume standard layout where projectPath is the root containing src
            SchemaInfo schemaInfo = schemaParserService.parseProject(sourceRoot, gitUrl);
            log.info("Project source parsed. Found {} entities.", schemaInfo.getEntities().size());

            Path baseOutputPath = Paths.get(processedSchemaBasePath);
            Files.createDirectories(baseOutputPath);

            String schemaFileName = "schema-" + safeUrlToFilePart(gitUrl) + "-" + UUID.randomUUID() + ".json";
            schemaFilePath = baseOutputPath.resolve(schemaFileName);

            schemaParserService.writeSchemaToJsonFile(schemaInfo, schemaFilePath);
            log.info("Schema IR saved to: {}", schemaFilePath);

            return schemaFilePath;

        } catch (Exception e) {
            log.error("Failed to process project and generate schema from Git URL: {}", gitUrl, e);
            if (projectPath != null && Files.exists(projectPath)) {
                if (cleanupClone) {
                    log.info("Attempting cleanup due to error for path: {}", projectPath);
                    try {
                        EntityFileProcessor.deleteDirectoryRecursively(projectPath);
                    } catch (IOException cleanupEx) {
                        log.error("Failed to cleanup cloned project directory after error: {}", projectPath, cleanupEx);
                    }
                } else {
                    log.warn("Error occurred, but cleanupClone=false. Leaving directory: {}", projectPath);
                }
            }
            throw new RuntimeException("Failed to process project/generate schema from URL: " + gitUrl + ". Reason: " + e.getMessage(), e);
        } finally {
            if (schemaFilePath != null && cleanupClone && projectPath != null && Files.exists(projectPath)) {
                log.info("Processing successful. Cleaning up cloned project path: {}", projectPath);
                try {
                    EntityFileProcessor.deleteDirectoryRecursively(projectPath);
                } catch (IOException e) {
                    log.error("Failed to cleanup cloned project directory after successful processing: {}", projectPath, e);
                }
            } else if (cleanupClone && projectPath != null) {
                log.info("Cleanup requested but processing may have failed or path was null/deleted. Path: {}", projectPath);
            }
        }
    }

    // REMOVED unused overload
    // public Path analyzeAndProcessProject(String gitUrl) { ... }

    private String safeUrlToFilePart(String url) {
        return url.replaceAll("[^a-zA-Z0-9.\\-_]", "_") // Allow dots, hyphens, underscores
                .replaceAll("_+", "_")
                .substring(0, Math.min(url.length(), 50));
    }
}