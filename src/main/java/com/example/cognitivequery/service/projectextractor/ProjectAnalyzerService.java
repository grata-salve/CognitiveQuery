package com.example.cognitivequery.service.projectextractor;

import com.example.cognitivequery.model.ir.SchemaInfo;
import com.example.cognitivequery.service.parser.SchemaParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectAnalyzerService {
    private final GitCloneService gitCloneService;
    private final SchemaParserService schemaParserService;

    @Value("${app.analysis.output.base-path:/tmp/cognitivequery/processed}")
    private String processedSchemaBasePath;

    // Formatter for timestamp in file names
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");


    /**
     * Clones a Git repository, parses JPA entities into a schema IR (JSON),
     * saves the IR to a user-specific file, and returns the path to that file.
     * Optionally cleans up the original clone.
     *
     * @param gitUrl       The URL of the Git repository.
     * @param telegramId   The Telegram ID of the user requesting the analysis (for path creation).
     * @param cleanupClone Whether to delete the original cloned repository after processing.
     * @return Path to the generated JSON schema file.
     * @throws RuntimeException         if cloning, parsing, or saving fails.
     * @throws IllegalArgumentException if telegramId is null or empty.
     */
    public Path analyzeAndProcessProject(String gitUrl, String telegramId, boolean cleanupClone) {
        if (!StringUtils.hasText(telegramId)) {
            throw new IllegalArgumentException("Telegram ID cannot be null or empty for creating user-specific path.");
        }

        Path projectPath = null;
        Path schemaFilePath = null;
        try {
            log.info("[TGID:{}] Starting analysis and schema parsing for Git URL: {}", telegramId, gitUrl);
            projectPath = gitCloneService.cloneRepository(gitUrl);
            log.info("[TGID:{}] Repository cloned successfully to: {}", telegramId, projectPath);

            Path sourceRoot = projectPath;
            SchemaInfo schemaInfo = schemaParserService.parseProject(sourceRoot, gitUrl);
            log.info("[TGID:{}] Project source parsed. Found {} entities.", telegramId, schemaInfo.getEntities().size());

            // --- Path Creation Logic Updated ---
            // 1. Base path
            Path baseOutputPath = Paths.get(processedSchemaBasePath);
            // 2. User-specific sub-directory
            Path userOutputPath = baseOutputPath.resolve(telegramId); // Use telegramId as subfolder name
            // 3. Ensure user directory exists
            Files.createDirectories(userOutputPath);

            // 4. Create a unique file name (e.g., using timestamp and repo name part)
            String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP_FORMATTER);
            String safeRepoPart = safeUrlToFilePart(gitUrl);
            String schemaFileName = String.format("schema-%s-%s.json", safeRepoPart, timestamp);
            schemaFilePath = userOutputPath.resolve(schemaFileName);
            // --- End Path Creation Logic ---

            schemaParserService.writeSchemaToJsonFile(schemaInfo, schemaFilePath);
            log.info("[TGID:{}] Schema IR saved to: {}", telegramId, schemaFilePath);

            return schemaFilePath;

        } catch (Exception e) {
            log.error("[TGID:{}] Failed to process project and generate schema from Git URL: {}", telegramId, gitUrl, e);
            // Cleanup on error
            if (projectPath != null && Files.exists(projectPath)) {
                if (cleanupClone) {
                    log.info("[TGID:{}] Attempting cleanup due to error for path: {}", telegramId, projectPath);
                    try {
                        EntityFileProcessor.deleteDirectoryRecursively(projectPath);
                    } catch (IOException cleanupEx) {
                        log.error("[TGID:{}] Failed to cleanup cloned project directory after error: {}", telegramId, projectPath, cleanupEx);
                    }
                } else {
                    log.warn("[TGID:{}] Error occurred, but cleanupClone=false. Leaving directory: {}", telegramId, projectPath);
                }
            }
            throw new RuntimeException("Failed to process project/generate schema from URL: " + gitUrl + ". Reason: " + e.getMessage(), e);
        } finally {
            // Cleanup on success
            if (schemaFilePath != null && cleanupClone && projectPath != null && Files.exists(projectPath)) {
                log.info("[TGID:{}] Processing successful. Cleaning up cloned project path: {}", telegramId, projectPath);
                try {
                    EntityFileProcessor.deleteDirectoryRecursively(projectPath);
                } catch (IOException e) {
                    log.error("[TGID:{}] Failed to cleanup cloned project directory after successful processing: {}", telegramId, projectPath, e);
                }
            } else if (cleanupClone && projectPath != null) {
                log.info("[TGID:{}] Cleanup requested but processing may have failed or path was null/deleted. Path: {}", telegramId, projectPath);
            }
        }
    }


    private String safeUrlToFilePart(String url) {
        String cleaned = url.replaceAll("[^a-zA-Z0-9.\\-_]", "_")
                .replaceAll("_+", "_");
        int maxLength = 50;
        return cleaned.substring(0, Math.min(cleaned.length(), maxLength));
    }
}