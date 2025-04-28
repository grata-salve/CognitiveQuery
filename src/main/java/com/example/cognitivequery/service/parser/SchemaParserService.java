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
        log.info("Starting schema parsing for project source root at: {}", projectSourcePath);
        SchemaInfo schemaInfo = new SchemaInfo();
        schemaInfo.setRepositoryUrl(repositoryUrl);
        schemaInfo.setAnalysisTimestamp(LocalDateTime.now());

        // --- Corrected Symbol Solver Setup ---
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        // 1. Solver for JDK classes
        typeSolver.add(new ReflectionTypeSolver(false));

        // 2. Solver for the project's own source files
        // Find potential source roots (e.g., src/main/java)
        List<Path> sourceRoots = findSourceRoots(projectSourcePath);
        if (sourceRoots.isEmpty()) {
            log.warn("Could not find standard Java source roots (e.g., src/main/java) within {}. Using project root as source path, symbol resolution may be incomplete.", projectSourcePath);
            // Fallback: Add the project root itself, hoping sources are directly there
            if (Files.isDirectory(projectSourcePath)) {
                typeSolver.add(new JavaParserTypeSolver(projectSourcePath.toFile()));
            } else {
                log.error("Project source path is not a directory: {}", projectSourcePath);
                throw new IOException("Project source path is not a directory: " + projectSourcePath);
            }
        } else {
            log.info("Found source roots: {}", sourceRoots);
            for (Path root : sourceRoots) {
                typeSolver.add(new JavaParserTypeSolver(root.toFile()));
            }
        }

        // 3. Configure Parser to use the solver
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        ParserConfiguration parserConfig = new ParserConfiguration().setSymbolResolver(symbolSolver);
        // --- End Symbol Solver Setup ---

        Map<String, JpaAnnotationVisitor.MappedSuperclassInfo> mappedSuperclasses = new HashMap<>();
        List<EmbeddableInfo> embeddables = new ArrayList<>();
        schemaInfo.setEmbeddables(embeddables);
        Map<String, Path> allJavaFiles = findAllJavaFiles(projectSourcePath);

        // First pass: Parse MappedSuperclasses and Embeddables
        log.debug("Starting first pass: Parsing MappedSuperclasses and Embeddables from {} files", allJavaFiles.size());
        for (Path javaFile : allJavaFiles.values()) { // Iterate over values directly
            try {
                // Parse using the configured parser
                CompilationUnit cu = StaticJavaParser.parse(javaFile, parserConfig.getCharacterEncoding()); // Pass config
                JpaAnnotationVisitor visitor = new JpaAnnotationVisitor(mappedSuperclasses, embeddables);
                visitor.visit(cu, JpaAnnotationVisitor.ProcessingPass.FIRST_PASS);
            } catch (Exception e) {
                log.warn("Failed to parse Java file during first pass: {} - {}", javaFile, e.getMessage(), e);
            }
        }
        log.info("First pass complete. Found {} mapped superclasses, {} embeddables.",
                mappedSuperclasses.size(), schemaInfo.getEmbeddables() != null ? schemaInfo.getEmbeddables().size() : 0);


        // Second pass: Parse Entities
        log.debug("Starting second pass: Parsing Entities");
        schemaInfo.setEntities(new ArrayList<>());
        for (Path javaFile : allJavaFiles.values()) {
            try {
                CompilationUnit cu = StaticJavaParser.parse(javaFile, parserConfig.getCharacterEncoding()); // Pass config
                JpaAnnotationVisitor visitor = new JpaAnnotationVisitor(mappedSuperclasses, embeddables, schemaInfo);
                visitor.visit(cu, JpaAnnotationVisitor.ProcessingPass.SECOND_PASS);
            } catch (Exception e) {
                log.warn("Failed to parse Java file during second pass: {} - {}", javaFile, e.getMessage(), e);
            }
        }
        log.info("Second pass complete. Parsed {} entities.", schemaInfo.getEntities() != null ? schemaInfo.getEntities().size() : 0);

        return schemaInfo;
    }

    // ... (writeSchemaToJsonFile method remains the same) ...
    public void writeSchemaToJsonFile(SchemaInfo schemaInfo, Path outputFile) throws IOException {
        log.info("Writing parsed schema to JSON file: {}", outputFile);
        Files.createDirectories(outputFile.getParent());
        objectMapper.writeValue(outputFile.toFile(), schemaInfo);
        log.info("Schema successfully written to {}", outputFile);
    }


    // Helper to find standard Java source roots (like src/main/java)
    private List<Path> findSourceRoots(Path projectRoot) throws IOException {
        List<Path> roots = new ArrayList<>();
        Path srcMainJava = projectRoot.resolve("src").resolve("main").resolve("java");
        if (Files.isDirectory(srcMainJava)) {
            roots.add(srcMainJava);
        }
        // Add other potential roots if needed (e.g., src/generated/java)
        // Or, as a fallback, search for any 'java' directory under 'src'
        if (roots.isEmpty() && Files.isDirectory(projectRoot.resolve("src"))) {
            try (Stream<Path> walk = Files.walk(projectRoot.resolve("src"), 3)) { // Limit depth
                walk.filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().equals("java"))
                        .forEach(roots::add);
            }
        }

        return roots;
    }

    private Map<String, Path> findAllJavaFiles(Path projectSourcePath) throws IOException {
        // ... (implementation remains the same) ...
        Map<String, Path> fileMap = new HashMap<>();
        if (!Files.exists(projectSourcePath) || !Files.isDirectory(projectSourcePath)) {
            log.error("Project source path does not exist or is not a directory: {}", projectSourcePath);
            return fileMap;
        }
        try (Stream<Path> walk = Files.walk(projectSourcePath)) {
            walk
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            // Use a more robust key, like the fully qualified class name if possible,
                            // but relative path is a reasonable fallback.
                            String key = projectSourcePath.relativize(path).toString();
                            fileMap.put(key, path);
                        } catch (IllegalArgumentException e) {
                            // Handle cases where relativize might fail (e.g., different drives on Windows)
                            log.warn("Could not relativize path {}, using absolute path as key.", path, e);
                            fileMap.put(path.toString(), path); // Fallback to absolute path
                        }
                    });
        }
        return fileMap;
    }
}