package com.example.cognitivequery.service.projectextractor;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class EntityFileProcessor {

    /**
     * Processes a project directory to:
     * 1. Identify database entity model files
     * 2. Create an "Entities" directory in the target location
     * 3. Move all entity files to that directory
     * 4. Delete all other files from the project
     *
     * @param projectPath Path to the project directory
     * @param targetPath  Path where the Entities directory should be created (can be same as projectPath)
     * @return Number of preserved entity files
     */
    public int processProjectFiles(String projectPath, String targetPath, List<String> entityFileNames) throws IOException {

        // Create Entities directory
        Path entitiesDir = Paths.get(targetPath, "entities");
        if (!Files.exists(entitiesDir)) {
            Files.createDirectories(entitiesDir);
        }

        // Find all entity files in the project
        List<Path> entityFilePaths = findEntityFiles(projectPath, entityFileNames);

        // Move entity files to the Entities directory
        for (Path entityFilePath : entityFilePaths) {
            Path targetFile = entitiesDir.resolve(entityFilePath.getFileName());
            Files.copy(entityFilePath, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }

        // Delete all files from the project (keeping the Entities directory)
        deleteAllFilesExceptEntities(projectPath, entitiesDir);

        return entityFileNames.size();
    }

    /**
     * Finds the full paths of all entity files in the project
     */
    private List<Path> findEntityFiles(String projectPath, List<String> entityFileNames) throws IOException {
        try (Stream<Path> walk = Files.walk(Paths.get(projectPath))) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(path -> entityFileNames.contains(path.getFileName().toString()))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Deletes all files and directories from the project except for the Entities directory
     */
    private void deleteAllFilesExceptEntities(String projectPath, Path entitiesDir) throws IOException {
        Path projectDirPath = Paths.get(projectPath);

        // If project path and target path are the same, we need to be careful not to delete the Entities directory
        try (Stream<Path> walk = Files.walk(projectDirPath)) {
            List<Path> pathsToDelete = walk
                    .filter(path -> !path.equals(projectDirPath)) // Don't delete the root project dir
                    .filter(path -> !path.startsWith(entitiesDir)) // Don't delete the Entities dir or its contents
                    .sorted((p1, p2) -> -p1.compareTo(p2)) // Sort in reverse order to delete files before directories
                    .toList();

            for (Path path : pathsToDelete) {
                Files.deleteIfExists(path);
            }
        }
    }
}