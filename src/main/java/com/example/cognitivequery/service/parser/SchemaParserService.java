package com.example.cognitivequery.service.parser;

import com.example.cognitivequery.model.ir.*; // Import all IR models
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
@Slf4j
public class SchemaParserService {

    private final ObjectMapper objectMapper;

    public SchemaParserService(ObjectMapper objectMapper) {
        // Inject ObjectMapper configured by Spring Boot (includes JavaTimeModule by default)
        this.objectMapper = objectMapper.copy(); // Use a copy to apply local settings
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        // JavaTimeModule should be registered by Spring Boot automatically
        // this.objectMapper.registerModule(new JavaTimeModule());
        // this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public SchemaInfo parseProject(Path projectSourcePath, String repositoryUrl) throws IOException {
        log.info("Starting schema parsing for project source at: {}", projectSourcePath);
        SchemaInfo schemaInfo = new SchemaInfo();
        schemaInfo.setRepositoryUrl(repositoryUrl);
        schemaInfo.setAnalysisTimestamp(LocalDateTime.now());

        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver(false)); // Use reflection solver only for JDK classes
        typeSolver.add(new JavaParserTypeSolver(projectSourcePath.toFile()));
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        StaticJavaParser.getConfiguration().setSymbolResolver(symbolSolver);

        Map<String, JpaAnnotationVisitor.MappedSuperclassInfo> mappedSuperclasses = new HashMap<>();
        Map<String, Path> allJavaFiles = findAllJavaFiles(projectSourcePath); // Find all files once


        // First pass: Parse MappedSuperclasses and Embeddables
        log.debug("Starting first pass: Parsing MappedSuperclasses and Embeddables from {} files", allJavaFiles.size());
        for (Map.Entry<String, Path> entry : allJavaFiles.entrySet()) {
            Path javaFile = entry.getValue();
            try {
                CompilationUnit cu = StaticJavaParser.parse(javaFile);
                // Pass non-null list for embeddables
                JpaAnnotationVisitor visitor = new JpaAnnotationVisitor(mappedSuperclasses, schemaInfo.getEmbeddables() != null ? schemaInfo.getEmbeddables() : new ArrayList<>());
                visitor.visit(cu, JpaAnnotationVisitor.ProcessingPass.FIRST_PASS); // Use Enum for clarity
            } catch (Exception e) {
                log.warn("Failed to parse Java file during first pass: {} - {}", javaFile, e.getMessage(), e); // Log stacktrace on WARN
            }
        }
        log.info("First pass complete. Found {} mapped superclasses, {} embeddables.",
                mappedSuperclasses.size(), schemaInfo.getEmbeddables().size());


        // Second pass: Parse Entities, resolving inheritance and embeddings
        log.debug("Starting second pass: Parsing Entities");
        for (Map.Entry<String, Path> entry : allJavaFiles.entrySet()) {
            Path javaFile = entry.getValue();
            try {
                CompilationUnit cu = StaticJavaParser.parse(javaFile);
                // Pass schemaInfo to collect entities
                JpaAnnotationVisitor visitor = new JpaAnnotationVisitor(mappedSuperclasses, schemaInfo.getEmbeddables(), schemaInfo);
                visitor.visit(cu, JpaAnnotationVisitor.ProcessingPass.SECOND_PASS);
            } catch (Exception e) {
                log.warn("Failed to parse Java file during second pass: {} - {}", javaFile, e.getMessage(), e);
            }
        }
        log.info("Second pass complete. Parsed {} entities.", schemaInfo.getEntities().size());

        // Clean up parser configuration if necessary (though StaticJavaParser is generally okay)
        // StaticJavaParser.getConfiguration().setSymbolResolver(null);

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
        try (Stream<Path> walk = Files.walk(projectSourcePath)) {
            walk
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        // Create a key based on relative path if possible, or just file name
                        String key = projectSourcePath.relativize(path).toString();
                        fileMap.put(key, path);
                    });
        }
        return fileMap;
    }
}