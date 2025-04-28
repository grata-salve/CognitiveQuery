package com.example.cognitivequery.service.projectextractor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class EntityFileProcessor {

    /**
     * Processes a project directory to:
     * 1. Identify database entity model files.
     * 2. Create a unique "processed_entities_{uuid}" directory in a specified base target location.
     * 3. Copy all found entity files to that directory.
     *
     * @param projectPath     Path to the *cloned* project directory.
     * @param baseTargetPath  Base path where the processed entities directory should be created.
     * @param entityFileNames List of entity file names (e.g., "User.java").
     * @return Path to the newly created directory containing copied entity files.
     * @throws IOException If an I/O error occurs.
     */
    public Path processAndCopyEntities(Path projectPath, Path baseTargetPath, List<String> entityFileNames) throws IOException {
        String uniqueDirName = "processed_entities_" + UUID.randomUUID();
        Path uniqueEntitiesDir = baseTargetPath.resolve(uniqueDirName);
        Files.createDirectories(uniqueEntitiesDir);
        log.info("Created unique directory for processed entities: {}", uniqueEntitiesDir);

        if (entityFileNames == null || entityFileNames.isEmpty()) {
            log.warn("No entity file names provided for project path: {}. Returning empty directory.", projectPath);
            return uniqueEntitiesDir;
        }

        List<Path> entityFilePaths = findEntityFiles(projectPath.toString(), entityFileNames);
        log.info("Found {} entity files to copy from {}", entityFilePaths.size(), projectPath);

        int copiedCount = 0;
        for (Path entityFilePath : entityFilePaths) {
            try {
                Path targetFile = uniqueEntitiesDir.resolve(entityFilePath.getFileName());
                // Ensure source file exists before attempting copy
                if (Files.exists(entityFilePath)) {
                    Files.copy(entityFilePath, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    copiedCount++;
                } else {
                    log.warn("Source entity file not found, skipping copy: {}", entityFilePath);
                }
            } catch (IOException e) {
                log.error("Failed to copy entity file {} to {}", entityFilePath, uniqueEntitiesDir, e);
            }
        }
        log.info("Successfully copied {} entity files to {}", copiedCount, uniqueEntitiesDir);

        return uniqueEntitiesDir;
    }

    private List<Path> findEntityFiles(String projectPath, List<String> entityFileNames) throws IOException {
        Path startPath = Paths.get(projectPath);
        if (!Files.exists(startPath)) {
            log.warn("Project path does not exist for finding entity files: {}", projectPath);
            return List.of(); // Return empty list if path doesn't exist
        }
        try (Stream<Path> walk = Files.walk(startPath)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName() != null && entityFileNames.contains(path.getFileName().toString()))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Helper method to delete a directory and its contents recursively.
     * Use with caution!
     *
     * @param directory Path to the directory to delete.
     * @throws IOException If an I/O error occurs during deletion.
     */
    public static void deleteDirectoryRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            log.debug("Directory to delete does not exist: {}", directory);
            return;
        }
        log.warn("Recursively deleting directory: {}", directory);
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                            log.debug("Deleted path: {}", path);
                        } catch (IOException e) {
                            // Log aggressively, as failure here means incomplete cleanup
                            log.error("!!! Failed to delete path during recursive cleanup: {}", path, e);
                        }
                    });
        }
        log.info("Successfully initiated recursive deletion for directory: {}", directory);
    }
}