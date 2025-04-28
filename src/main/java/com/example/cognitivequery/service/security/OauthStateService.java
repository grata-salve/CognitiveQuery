package com.example.cognitivequery.service.security;

import com.example.cognitivequery.model.OauthState;
import com.example.cognitivequery.repository.OauthStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OauthStateService {

    private final OauthStateRepository oauthStateRepository;

    @Value("${app.oauth.state.ttl.minutes:10}")
    private long stateTtlMinutes;

    @Transactional
    public String createState(String telegramId) {
        // Cleanup old states before creating a new one (optional)
        // cleanupExpiredStates();
        String state = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(stateTtlMinutes);
        OauthState oauthState = new OauthState(state, telegramId, expiresAt);
        oauthStateRepository.save(oauthState);
        return state;
    }

    @Transactional
    public Optional<String> validateAndConsumeState(String state) {
        Optional<OauthState> oauthStateOpt = oauthStateRepository.findByStateAndExpiresAtAfter(state, LocalDateTime.now());
        if (oauthStateOpt.isPresent()) {
            String telegramId = oauthStateOpt.get().getTelegramId();
            oauthStateRepository.delete(oauthStateOpt.get()); // Delete state after use
            return Optional.of(telegramId);
        }
        return Optional.empty();
    }

    // Periodic cleanup of expired states (e.g., every hour)
    @Scheduled(fixedRate = 3600000) // 3600000 ms = 1 hour
    @Transactional
    public void cleanupExpiredStates() {
        oauthStateRepository.deleteByExpiresAtBefore(LocalDateTime.now());
    }
}