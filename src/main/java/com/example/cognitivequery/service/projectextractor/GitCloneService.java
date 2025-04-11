package com.example.cognitivequery.service.projectextractor;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class GitCloneService {
    public Path cloneRepository(String repoUrl) throws GitAPIException, IOException {
        Path downloadsDir = Paths.get("/Users/vladlenprokopenko/Downloads", "git-project-" + System.nanoTime());
        Files.createDirectories(downloadsDir);
        Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(downloadsDir.toFile())
                .call();
        return downloadsDir;
    }
}
