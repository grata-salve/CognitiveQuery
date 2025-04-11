package com.example.cognitivequery.service.projectextractor;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
@RequiredArgsConstructor
public class ProjectAnalyzerService {
    private final GitCloneService gitCloneService;
    private final EntityScannerService entityScannerService;
    private final EntityFileProcessor entityFileProcessor;

    public void processProject(String gitUrl) {
        try {
            Path projectPath = gitCloneService.cloneRepository(gitUrl);
            var paths = entityScannerService.scanForEntityClasses(String.valueOf(projectPath));
            entityFileProcessor.processProjectFiles(
                    projectPath.toString(), projectPath.toString(), paths);
        } catch (Exception e) {
            throw new RuntimeException("Failed to process project", e);
        }
    }
}
