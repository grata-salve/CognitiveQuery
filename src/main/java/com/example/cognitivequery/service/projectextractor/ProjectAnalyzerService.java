package com.example.cognitivequery.service.projectextractor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files; // Import Files
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectAnalyzerService {
    private final GitCloneService gitCloneService;
    private final EntityScannerService entityScannerService;
    private final EntityFileProcessor entityFileProcessor;

    @Value("${app.analysis.output.base-path:/tmp/cognitivequery/processed}")
    private String processedEntitiesBasePath;


    /**
     * Clones a Git repository, scans for entity classes, copies them to a unique directory,
     * and returns the path to that directory. Optionally cleans up the original clone.
     *
     * @param gitUrl The URL of the Git repository.
     * @param cleanupClone Whether to delete the original cloned repository after processing.
     * @return Path to the directory containing the processed entity files.
     * @throws RuntimeException if cloning, scanning, or processing fails.
     */
    public Path analyzeAndProcessProject(String gitUrl, boolean cleanupClone) {
        Path projectPath = null;
        try {
            log.info("Starting analysis for Git URL: {}", gitUrl);
            projectPath = gitCloneService.cloneRepository(gitUrl);
            log.info("Repository cloned successfully to: {}", projectPath);

            List<String> entityFileNames = entityScannerService.scanForEntityClasses(projectPath.toString());
            log.info("Found {} potential entity files: {}", entityFileNames.size(), entityFileNames);

            Path baseTargetPath = Paths.get(processedEntitiesBasePath);
            // Ensure base target path exists
            if (!Files.exists(baseTargetPath)) {
                Files.createDirectories(baseTargetPath);
                log.info("Created base target directory: {}", baseTargetPath);
            }

            Path processedEntitiesDir = entityFileProcessor.processAndCopyEntities(projectPath, baseTargetPath, entityFileNames);
            log.info("Entity files processed and copied to: {}", processedEntitiesDir);

            return processedEntitiesDir;

        } catch (Exception e) {
            log.error("Failed to process project from Git URL: {}", gitUrl, e);
            throw new RuntimeException("Failed to process project from URL: " + gitUrl + ". Reason: " + e.getMessage(), e);
        } finally {
            if (cleanupClone && projectPath != null && Files.exists(projectPath)) {
                try {
                    EntityFileProcessor.deleteDirectoryRecursively(projectPath);
                } catch (IOException e) {
                    log.error("Failed to cleanup cloned project directory: {}", projectPath, e);
                }
            } else if (cleanupClone) {
                log.warn("Cleanup requested but project path was null or did not exist: {}", projectPath);
            }
        }
    }

}