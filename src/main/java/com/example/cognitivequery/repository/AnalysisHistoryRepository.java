package com.example.cognitivequery.repository;

import com.example.cognitivequery.model.AnalysisHistory;
import com.example.cognitivequery.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AnalysisHistoryRepository extends JpaRepository<AnalysisHistory, Long> {

    // Find by user, repo URL, and commit hash (for checking exact version)
    Optional<AnalysisHistory> findByAppUserAndRepositoryUrlAndCommitHash(AppUser appUser, String repositoryUrl, String commitHash);

    // Find the latest entry for a user (default for /query)
    Optional<AnalysisHistory> findFirstByAppUserOrderByAnalyzedAtDesc(AppUser appUser);

    // Find the latest entry for a user and specific repo URL (for /use_schema)
    Optional<AnalysisHistory> findFirstByAppUserAndRepositoryUrlOrderByAnalyzedAtDesc(AppUser appUser, String repositoryUrl);

    // Find all entries for a user and repo URL (for cleanup)
    List<AnalysisHistory> findByAppUserAndRepositoryUrl(AppUser appUser, String repositoryUrl);

    // Find all entries for a user (for /list_schemas)
    List<AnalysisHistory> findByAppUserOrderByAnalyzedAtDesc(AppUser appUser);
}