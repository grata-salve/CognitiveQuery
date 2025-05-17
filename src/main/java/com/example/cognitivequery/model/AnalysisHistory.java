package com.example.cognitivequery.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "analysis_history", indexes = {
        @Index(columnList = "app_user_id, repositoryUrl, analyzedAt DESC") // Index for faster lookups
})
@Getter
@Setter
@NoArgsConstructor
public class AnalysisHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) // Relationship to the user
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser appUser;

    @Column(nullable = false, length = 1024)
    private String repositoryUrl;

    @Column(nullable = false, length = 40) // Commit hash at the time of analysis
    private String commitHash;

    @Column(nullable = false, length = 2048) // Path to the generated schema JSON file
    private String schemaFilePath;

    @Column(nullable = false) // Timestamp of the analysis
    private LocalDateTime analyzedAt;

    // DB Credentials related to this specific analysis
    @Column(length = 255)
    private String dbHost;
    @Column
    private Integer dbPort;
    @Column(length = 100)
    private String dbName;
    @Column(length = 100)
    private String dbUser;
    @Column(length = 512) // Store encrypted password
    private String dbPasswordEncrypted;
    // If using salt/IV with encryption, add a field here e.g., private String dbPasswordSaltOrIv;

    public AnalysisHistory(AppUser appUser, String repositoryUrl, String commitHash, String schemaFilePath) {
        this.appUser = appUser;
        this.repositoryUrl = repositoryUrl;
        this.commitHash = commitHash;
        this.schemaFilePath = schemaFilePath;
        this.analyzedAt = LocalDateTime.now();
    }

    // Helper to check if credentials are set for this history entry
    public boolean hasCredentials() {
        return dbHost != null && !dbHost.isBlank() &&
                dbPort != null &&
                dbName != null && !dbName.isBlank() &&
                dbUser != null && !dbUser.isBlank() &&
                dbPasswordEncrypted != null && !dbPasswordEncrypted.isBlank();
    }
}