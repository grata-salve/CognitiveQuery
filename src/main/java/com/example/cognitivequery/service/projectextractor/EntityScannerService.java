package com.example.cognitivequery.service.projectextractor;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@Service
public class EntityScannerService {

    // Regex patterns to identify database entity classes
    private static final Pattern ENTITY_ANNOTATION_PATTERN = Pattern.compile("@Entity|@Table|@Document");
    private static final Pattern JPA_IMPORTS_PATTERN = Pattern.compile("import\\s+javax\\.persistence\\.|import\\s+jakarta\\.persistence\\.|import\\s+org\\.hibernate\\.");
    private static final Pattern COLUMN_ANNOTATION_PATTERN = Pattern.compile("@Column|@Id|@GeneratedValue");

    /**
     * Scans a project directory and returns a list of database entity class file names
     *
     * @param projectPath Path to the Git project
     * @return List of entity class file names (e.g., "Project.java")
     */
    public List<String> scanForEntityClasses(String projectPath) {
        List<String> entityFiles = new ArrayList<>();

        try {
            // Find all Java files recursively
            List<Path> javaFiles = findJavaFiles(projectPath);

            // Analyze each Java file to determine if it's a database entity
            for (Path javaFile : javaFiles) {
                String content = new String(Files.readAllBytes(javaFile));

                if (isDatabaseEntityClass(content)) {
                    // Extract just the filename, not the full path
                    String fileName = javaFile.getFileName().toString();
                    entityFiles.add(fileName);
                }
            }

        } catch (IOException e) {
            throw new RuntimeException("Error scanning project directory: " + e.getMessage(), e);
        }

        return entityFiles;
    }

    /**
     * Finds all Java files in the given directory and its subdirectories
     */
    private List<Path> findJavaFiles(String directoryPath) throws IOException {
        try (Stream<Path> walk = Files.walk(Paths.get(directoryPath))) {
            return walk.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    /**
     * Determines if a Java file represents a database entity class
     * Specifically looks for JPA/Hibernate annotations indicating database mapping
     */
    private boolean isDatabaseEntityClass(String content) {
        // Check for explicit DTO markers to exclude them
        if (content.contains("DTO") || content.contains("Dto")) {
            return false;
        }

        // Primary check: Must have @Entity, @Table or @Document annotation
        if (!ENTITY_ANNOTATION_PATTERN.matcher(content).find()) {
            return false;
        }

        // Secondary checks to increase confidence
        // 1. Should have JPA/Hibernate imports
        boolean hasJpaImports = JPA_IMPORTS_PATTERN.matcher(content).find();

        // 2. Should have database column-related annotations
        boolean hasColumnAnnotations = COLUMN_ANNOTATION_PATTERN.matcher(content).find();

        // Must pass both the entity check and at least one of the secondary checks
        return hasJpaImports || hasColumnAnnotations;
    }
}