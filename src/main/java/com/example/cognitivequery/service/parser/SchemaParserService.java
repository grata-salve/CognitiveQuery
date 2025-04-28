package com.example.cognitivequery.service.parser;

import com.example.cognitivequery.model.ir.EmbeddableInfo;
import com.example.cognitivequery.model.ir.SchemaInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.CompilationUnit.Storage;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.ParserCollectionStrategy;
import com.github.javaparser.utils.ProjectRoot;
import com.github.javaparser.utils.SourceRoot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class SchemaParserService {

    private final ObjectMapper objectMapper;

    public SchemaParserService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public SchemaInfo parseProject(Path projectRootPath, String repositoryUrl) throws IOException {
        log.info("Starting schema parsing for project root at: {}", projectRootPath);
        SchemaInfo schemaInfo = new SchemaInfo();
        schemaInfo.setRepositoryUrl(repositoryUrl);
        schemaInfo.setAnalysisTimestamp(LocalDateTime.now());

        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver(false));

        ProjectRoot projectRoot = new ParserCollectionStrategy().collect(projectRootPath);
        List<SourceRoot> sourceRoots = projectRoot.getSourceRoots();

        if (sourceRoots.isEmpty()) {
            log.warn("!!! No source roots found by ParserCollectionStrategy in {}. Symbol resolution will likely fail.", projectRootPath);
            throw new IOException("No Java source roots found in the project path: " + projectRootPath);
        } else {
            log.info("Found {} source root(s): {}", sourceRoots.size(),
                    sourceRoots.stream().map(SourceRoot::getRoot).collect(Collectors.toList()));
            sourceRoots.forEach(sourceRoot -> typeSolver.add(new JavaParserTypeSolver(sourceRoot.getRoot().toFile())));
        }

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        ParserConfiguration parserConfig = new ParserConfiguration().setSymbolResolver(symbolSolver);

        Map<String, JpaAnnotationVisitor.MappedSuperclassInfo> mappedSuperclasses = new HashMap<>();
        List<EmbeddableInfo> embeddables = new ArrayList<>();
        schemaInfo.setEmbeddables(embeddables);

        List<CompilationUnit> allCus = new ArrayList<>();
        for (SourceRoot sourceRoot : sourceRoots) {
            log.info("Parsing source root: {}", sourceRoot.getRoot());
            try {
                sourceRoot.setParserConfiguration(parserConfig);
                List<ParseResult<CompilationUnit>> parseResults = sourceRoot.tryToParse("");
                for (ParseResult<CompilationUnit> result : parseResults) {
                    Optional<Path> filePathOpt = result.getResult() // Optional<CompilationUnit>
                            .flatMap(CompilationUnit::getStorage) // Optional<Storage>
                            .map(Storage::getPath); // Optional<Path>

                    if (result.isSuccessful() && result.getResult().isPresent()) {
                        allCus.add(result.getResult().get());
                    } else {
                        String problems = result.getProblems().stream().map(Object::toString).collect(Collectors.joining(", "));
                        String filePathStr = filePathOpt.map(Path::toString).orElse("UNKNOWN_FILE");
                        log.warn("Failed to parse file: {}. Problems: {}", filePathStr, problems);
                    }
                }
            } catch (IOException e) {
                log.error("Error parsing source root: {}", sourceRoot.getRoot(), e);
            }
        }

        if (allCus.isEmpty()) {
            log.error("No compilation units were successfully parsed from project sources at {}", projectRootPath);
            return schemaInfo;
        }
        log.info("Successfully parsed {} compilation units.", allCus.size());

        log.debug("Starting first pass: Parsing MappedSuperclasses and Embeddables");
        JpaAnnotationVisitor firstPassVisitor = new JpaAnnotationVisitor(mappedSuperclasses, embeddables);
        allCus.forEach(cu -> {
            try {
                firstPassVisitor.visit(cu, JpaAnnotationVisitor.ProcessingPass.FIRST_PASS);
            } catch (Exception e) {
                log.warn("Error during first pass visit for CU from {}: {}", cu.getStorage().map(s -> s.getPath().toString()).orElse("UNKNOWN"), e.getMessage());
            }
        });
        log.info("First pass complete. Found {} mapped superclasses, {} embeddables.", mappedSuperclasses.size(), embeddables.size());

        // Second pass
        log.debug("Starting second pass: Parsing Entities");
        schemaInfo.setEntities(new ArrayList<>());
        JpaAnnotationVisitor secondPassVisitor = new JpaAnnotationVisitor(mappedSuperclasses, embeddables, schemaInfo);
        allCus.forEach(cu -> {
            try {
                secondPassVisitor.visit(cu, JpaAnnotationVisitor.ProcessingPass.SECOND_PASS);
            } catch (Exception e) {
                log.warn("Error during second pass visit for CU from {}: {}", cu.getStorage().map(s -> s.getPath().toString()).orElse("UNKNOWN"), e.getMessage());
            }
        });
        log.info("Second pass complete. Parsed {} entities.", schemaInfo.getEntities().size());

        return schemaInfo;
    }

    public void writeSchemaToJsonFile(SchemaInfo schemaInfo, Path outputFile) throws IOException {
        log.info("Writing parsed schema to JSON file: {}", outputFile);
        Files.createDirectories(outputFile.getParent());
        objectMapper.writeValue(outputFile.toFile(), schemaInfo);
        log.info("Schema successfully written to {}", outputFile);
    }
}