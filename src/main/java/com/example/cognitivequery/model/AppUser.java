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
    private String email;
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuthProvider provider = AuthProvider.TELEGRAM;

    private LocalDateTime lastLogin;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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