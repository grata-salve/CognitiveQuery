package com.example.cognitivequery.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "app_users", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"telegramId"}), // Make telegramId unique
        @UniqueConstraint(columnNames = {"githubId", "provider"})
})
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "analysisHistories") // Avoid infinite loop in toString if bidirectional
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String telegramId;

    // GitHub related fields
    @Column(unique = true, nullable = true)
    private String githubId;
    @Column(nullable = true)
    private String githubLogin;
    private String name;
    private String email;
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuthProvider provider = AuthProvider.TELEGRAM;

    private LocalDateTime lastLogin; // Last GitHub login time
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Relationship to analysis history
    @OneToMany(mappedBy = "appUser", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<AnalysisHistory> analysisHistories = new ArrayList<>();

    @Column(nullable = false)
    private boolean visualizationEnabled = true;
    @Column(nullable = false)
    private boolean aiInsightsEnabled = true;

    @Column(nullable = false)
    private boolean dataModificationEnabled = false;

    @Column(nullable = false)
    private boolean showSqlEnabled = false;
    @Column(nullable = false)
    private boolean dryRunEnabled = false;

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
        this.email = email;
        this.avatarUrl = avatarUrl;
        if (this.provider == AuthProvider.TELEGRAM) {
            this.provider = AuthProvider.GITHUB;
        }
        this.lastLogin = LocalDateTime.now();
    }
}