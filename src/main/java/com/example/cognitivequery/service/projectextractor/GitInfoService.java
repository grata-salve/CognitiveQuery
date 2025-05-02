package com.example.cognitivequery.service.projectextractor;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Optional;

@Service
@Slf4j
public class GitInfoService {

    /**
     * Gets the commit hash of the HEAD (usually the default branch like main/master)
     * of a remote repository without cloning it.
     *
     * @param repoUrl The URL of the remote repository.
     * @return Optional containing the commit hash (ObjectId) as a String, or empty if not found or error occurs.
     */
    public Optional<String> getRemoteHeadCommitHash(String repoUrl) {
        log.debug("Attempting to get HEAD commit hash for remote URL: {}", repoUrl);
        LsRemoteCommand lsRemoteCommand = Git.lsRemoteRepository().setRemote(repoUrl).setHeads(true); // Look at heads only

        try {
            Collection<Ref> refs = lsRemoteCommand.call();
            // Find the reference for HEAD (points to the default branch)
            // Or fallback to common default branch names if HEAD is ambiguous
            Optional<Ref> headRef = refs.stream()
                    .filter(ref -> "HEAD".equals(ref.getName()) || ref.getName().endsWith("/main") || ref.getName().endsWith("/master"))
                    .findFirst();

            if (headRef.isPresent()) {
                Ref ref = headRef.get();
                // If HEAD points to another ref (e.g. refs/heads/main), resolve it
                ObjectId objectId = ref.getPeeledObjectId() != null ? ref.getPeeledObjectId() : ref.getObjectId();
                if (objectId != null) {
                    String commitHash = objectId.getName(); // Gets the SHA-1 hash string
                    log.info("Found remote HEAD commit hash for {}: {}", repoUrl, commitHash);
                    return Optional.of(commitHash);
                } else {
                    log.warn("Could not resolve ObjectId for HEAD ref of {}", repoUrl);
                }
            } else {
                log.warn("Could not find HEAD, main, or master ref for remote repository: {}", repoUrl);
                // Log all refs for debugging if needed
                // refs.forEach(r -> log.debug("Available ref: {} -> {}", r.getName(), r.getObjectId()));
            }
        } catch (GitAPIException e) {
            log.error("GitAPIException while getting remote head commit hash for {}: {}", repoUrl, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error while getting remote head commit hash for {}", repoUrl, e);
        }
        return Optional.empty();
    }
}