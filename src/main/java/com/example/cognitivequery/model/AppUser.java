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

    // Can be null if the user hasn't linked the account yet
    @Column(unique = true)
    private String githubId;

    // Can be null
    @Column
    private String githubLogin;

    // Display name from GitHub (can be null)
    private String name;

    // Email from GitHub (can be null or require scope)
    private String email;

    // Avatar URL from GitHub
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuthProvider provider = AuthProvider.TELEGRAM; // Main provider - telegram

    private LocalDateTime lastLogin; // Time of the last login via OAuth provider
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Constructor for a new user from Telegram
    public AppUser(String telegramId) {
        this.telegramId = telegramId;
        this.provider = AuthProvider.TELEGRAM; // Default
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
        this.name = (name != null) ? name : this.name; // Do not overwrite with null if the name already existed
        this.email = (email != null) ? email : this.email;
        this.avatarUrl = avatarUrl;
        this.provider = AuthProvider.GITHUB; // Indicate that the GitHub link is established
        this.lastLogin = LocalDateTime.now();
    }
}