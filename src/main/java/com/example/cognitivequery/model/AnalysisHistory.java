package com.example.cognitivequery.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "analysis_history")
@Getter
@Setter
@NoArgsConstructor
public class AnalysisHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser appUser;

    @Column(nullable = false, length = 1024)
    private String repositoryUrl;

    @Column(nullable = false, length = 40) // Commit hash at the time of analysis
    private String commitHash;

    @Column(nullable = false, length = 2048)
    private String schemaFilePath;

    @Column(nullable = false)
    private LocalDateTime analyzedAt;

    public AnalysisHistory(AppUser appUser, String repositoryUrl, String commitHash, String schemaFilePath) {
        this.appUser = appUser;
        this.repositoryUrl = repositoryUrl;
        this.commitHash = commitHash;
        this.schemaFilePath = schemaFilePath;
        this.analyzedAt = LocalDateTime.now();
    }
}