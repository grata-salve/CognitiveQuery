package com.example.cognitivequery.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "oauth_states")
@Getter
@Setter
@NoArgsConstructor
public class OauthState {

    @Id
    private String state; // Unique state (UUID)

    @Column(nullable = false)
    private String telegramId; // Telegram user ID

    @Column(nullable = false)
    private LocalDateTime expiresAt; // State expiration time

    public OauthState(String state, String telegramId, LocalDateTime expiresAt) {
        this.state = state;
        this.telegramId = telegramId;
        this.expiresAt = expiresAt;
    }
}