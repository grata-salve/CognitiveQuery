package com.example.cognitivequery.service.parser;

import com.example.cognitivequery.model.ir.*; // Import all IR models
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.javaparser.ParserConfiguration; // Import ParserConfiguration
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime; // Import LocalDateTime
import java.util.ArrayList;     // Import ArrayList
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
@Slf4j
public class SchemaParserService {

    private final ObjectMapper objectMapper;

    public SchemaParserService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public SchemaInfo parseProject(Path projectSourcePath, String repositoryUrl) throws IOException {
        log.info("Starting schema parsing for project source at: {}", projectSourcePath);
        SchemaInfo schemaInfo = new SchemaInfo();
        schemaInfo.setRepositoryUrl(repositoryUrl);
        schemaInfo.setAnalysisTimestamp(LocalDateTime.now()); // Now LocalDateTime is resolved

        // --- Correct Symbol Solver Setup ---
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver(false)); // For JDK types
        // Ensure the path exists and is a directory
        if (Files.isDirectory(projectSourcePath)) {
            typeSolver.add(new JavaParserTypeSolver(projectSourcePath.toFile()));
        } else {
            log.warn("Project source path is not a directory or does not exist: {}", projectSourcePath);
            // Proceeding without source solver, resolution might fail for project classes
        }

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);

        // Configure StaticJavaParser to use the symbol solver
        ParserConfiguration parserConfig = StaticJavaParser.getConfiguration();
        parserConfig.setSymbolResolver(symbolSolver);
        // --- End Symbol Solver Setup ---


        Map<String, JpaAnnotationVisitor.MappedSuperclassInfo> mappedSuperclasses = new HashMap<>();
        // Initialize embeddables list here
        List<EmbeddableInfo> embeddables = new ArrayList<>();
        schemaInfo.setEmbeddables(embeddables); // Set the initialized list

        Map<String, Path> allJavaFiles = findAllJavaFiles(projectSourcePath);


        // First pass: Parse MappedSuperclasses and Embeddables
        log.debug("Starting first pass: Parsing MappedSuperclasses and Embeddables from {} files", allJavaFiles.size());
        for (Map.Entry<String, Path> entry : allJavaFiles.entrySet()) {
            Path javaFile = entry.getValue();
            try {
                // Use the configured parser
                CompilationUnit cu = StaticJavaParser.parse(javaFile);
                JpaAnnotationVisitor visitor = new JpaAnnotationVisitor(mappedSuperclasses, embeddables); // Pass the initialized list
                visitor.visit(cu, JpaAnnotationVisitor.ProcessingPass.FIRST_PASS);
            } catch (Exception e) {
                log.warn("Failed to parse Java file during first pass: {} - {}", javaFile, e.getMessage(), e);
            }
        }
        log.info("First pass complete. Found {} mapped superclasses, {} embeddables.",
                mappedSuperclasses.size(), schemaInfo.getEmbeddables() != null ? schemaInfo.getEmbeddables().size() : 0);


        // Second pass: Parse Entities
        log.debug("Starting second pass: Parsing Entities");
        // Initialize entities list
        schemaInfo.setEntities(new ArrayList<>());
        for (Map.Entry<String, Path> entry : allJavaFiles.entrySet()) {
            Path javaFile = entry.getValue();
            try {
                CompilationUnit cu = StaticJavaParser.parse(javaFile);
                JpaAnnotationVisitor visitor = new JpaAnnotationVisitor(mappedSuperclasses, embeddables, schemaInfo);
                visitor.visit(cu, JpaAnnotationVisitor.ProcessingPass.SECOND_PASS);
            } catch (Exception e) {
                log.warn("Failed to parse Java file during second pass: {} - {}", javaFile, e.getMessage(), e);
            }
        }
        log.info("Second pass complete. Parsed {} entities.", schemaInfo.getEntities() != null ? schemaInfo.getEntities().size() : 0);

        return schemaInfo;
    }

    public void writeSchemaToJsonFile(SchemaInfo schemaInfo, Path outputFile) throws IOException {
        log.info("Writing parsed schema to JSON file: {}", outputFile);
        Files.createDirectories(outputFile.getParent());
        objectMapper.writeValue(outputFile.toFile(), schemaInfo);
        log.info("Schema successfully written to {}", outputFile);
    }


    private Map<String, Path> findAllJavaFiles(Path projectSourcePath) throws IOException {
        Map<String, Path> fileMap = new HashMap<>();
        // Check if the source path exists before walking
        if (!Files.exists(projectSourcePath) || !Files.isDirectory(projectSourcePath)) {
            log.error("Project source path does not exist or is not a directory: {}", projectSourcePath);
            return fileMap; // Return empty map
        }
        try (Stream<Path> walk = Files.walk(projectSourcePath)) {
            walk
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    // Filter out .DS_Store implicitly by checking .java suffix
                    .forEach(path -> {
                        String key = projectSourcePath.relativize(path).toString();
                        fileMap.put(key, path);
                    });
        }
        return fileMap;
    }
}