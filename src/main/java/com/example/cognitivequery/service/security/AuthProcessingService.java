package com.example.cognitivequery.service.security;

import com.example.cognitivequery.model.AppUser;
import com.example.cognitivequery.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthProcessingService {

    private final GithubApiService githubApiService;
    private final UserRepository userRepository;

    @Transactional
    public AppUser processGithubCallback(String code, String telegramId) {
        // 1. Exchange code for access token
        String accessToken = githubApiService.exchangeCodeForToken(code);
        log.debug("Obtained access token for Telegram ID: {}", telegramId);

        // 2. Get basic user info (which should include the public email)
        Map<String, Object> githubUserInfo = githubApiService.getUserInfo(accessToken);
        log.info("Fetched GitHub user info: {}", githubUserInfo);

        // 3. Extract data directly from githubUserInfo
        String githubId = String.valueOf(githubUserInfo.get("id"));
        String login = (String) githubUserInfo.get("login");
        String name = (String) githubUserInfo.get("name");
        // Get email directly from the user info map
        String email = (String) githubUserInfo.get("email"); // Will be null if not public or not present
        String avatarUrl = (String) githubUserInfo.get("avatar_url");

        // Log whether the email was found in the basic info
        if (email != null) {
            log.info("Using email found in basic user info for Telegram ID {}: {}", telegramId, email);
        } else {
            log.warn("Email field is null in basic user info for Telegram ID {}. It might be private on GitHub or not set.", telegramId);
        }

        // 4. Find or create user in DB
        AppUser appUser = userRepository.findByTelegramId(telegramId)
                .orElseGet(() -> {
                    log.info("Creating new AppUser for Telegram ID: {}", telegramId);
                    return new AppUser(telegramId);
                });

        // 5. Check if GitHub account already linked to another user
        Optional<AppUser> existingGithubUser = userRepository.findByGithubId(githubId);
        if (existingGithubUser.isPresent() && !existingGithubUser.get().getTelegramId().equals(telegramId)) {
            log.warn("GitHub account {} is already linked to another Telegram user {}", githubId, existingGithubUser.get().getTelegramId());
            throw new GithubAccountAlreadyLinkedException("GitHub account " + githubId + " is already linked to another user.");
        }

        // 6. Update user data (using the email obtained from basic info)
        appUser.updateFromGitHub(githubId, login, name, email, avatarUrl);
        AppUser savedUser = userRepository.save(appUser);
        log.info("Successfully linked GitHub account {} to Telegram ID {} and saved user: {}", githubId, telegramId, savedUser);

        return savedUser;
    }

    // Custom exception class
    public static class GithubAccountAlreadyLinkedException extends RuntimeException {
        public GithubAccountAlreadyLinkedException(String message) {
            super(message);
        }
    }
}