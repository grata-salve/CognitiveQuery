package com.example.cognitivequery.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import java.time.LocalDateTime;

@Entity
@Table(name = "app_users", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"telegramId", "provider"}),
        @UniqueConstraint(columnNames = {"githubId", "provider"})
})
@Getter
@Setter
@NoArgsConstructor
@ToString
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String telegramId;

    @Column(unique = true, nullable = true)
    private String githubId;

    @Column(nullable = true)
    private String githubLogin;

    private String name;

    private String email; // Email from GitHub (might be null)

    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuthProvider provider = AuthProvider.TELEGRAM;

    private LocalDateTime lastLogin;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Column(length = 1024) // Increased length for URL
    private String lastAnalyzedRepoUrl;

    @Column(length = 2048) // Path can be long
    private String processedEntitiesPath; // Path to the folder with copied .java files

    public AppUser(String telegramId) {
        this.telegramId = telegramId;
        this.provider = AuthProvider.TELEGRAM;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum AuthProvider {
        TELEGRAM,
        GITHUB
    }

    public void updateFromGitHub(String githubId, String login, String name, String email, String avatarUrl) {
        this.githubId = githubId;
        this.githubLogin = login;
        this.name = (name != null) ? name : this.name;
        this.email = email; // Use email obtained from basic user info
        this.avatarUrl = avatarUrl;
        // Update provider only if it was TELEGRAM before, avoid overwriting GITHUB with itself
        if (this.provider == AuthProvider.TELEGRAM) {
            this.provider = AuthProvider.GITHUB;
        }
        this.lastLogin = LocalDateTime.now();
    }

    public void setAnalysisResults(String repoUrl, String entitiesPath) {
        this.lastAnalyzedRepoUrl = repoUrl;
        this.processedEntitiesPath = entitiesPath;
        // Optionally add/update a 'lastAnalyzedAt' timestamp here
    }
}