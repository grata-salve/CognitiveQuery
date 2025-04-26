package com.example.cognitivequery.controller;

import com.example.cognitivequery.service.security.AuthProcessingService;
import com.example.cognitivequery.service.security.OauthStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Optional;

@RestController
@RequiredArgsConstructor
@Slf4j
public class GithubAuthController {

    private final OauthStateService oauthStateService;
    private final ClientRegistrationRepository clientRegistrationRepository;
    private final AuthProcessingService authProcessingService;

    @PostMapping("/api/auth/github/initiate")
    public ResponseEntity<Map<String, String>> initiateGithubAuth(@RequestBody Map<String, String> payload) {
        String telegramId = payload.get("telegramId");
        if (telegramId == null || telegramId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "telegramId is required"));
        }
        log.info("Initiating GitHub OAuth for Telegram ID: {}", telegramId);
        ClientRegistration clientRegistration = clientRegistrationRepository.findByRegistrationId("github");
        if (clientRegistration == null) {
            log.error("GitHub client registration not found!");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "GitHub client registration not configured"));
        }
        String state = oauthStateService.createState(telegramId);
        String authorizationUri = UriComponentsBuilder
                .fromUriString(clientRegistration.getProviderDetails().getAuthorizationUri())
                .queryParam("client_id", clientRegistration.getClientId())
                .queryParam("redirect_uri", clientRegistration.getRedirectUri())
                .queryParam("scope", String.join(" ", clientRegistration.getScopes()))
                .queryParam("response_type", "code")
                .queryParam("state", state)
                .build().encode().toUriString();
        log.info("Generated authorization URL for Telegram ID {}: {}", telegramId, authorizationUri);
        return ResponseEntity.ok(Map.of("authorizationUrl", authorizationUri));
    }

    @GetMapping("/login/oauth2/code/github")
    public ResponseEntity<String> handleGithubCallback(@RequestParam("code") String code, @RequestParam("state") String state) {
        log.info("Received GitHub callback with state: {}", state);

        Optional<String> telegramIdOpt = oauthStateService.validateAndConsumeState(state);

        if (telegramIdOpt.isEmpty()) {
            log.warn("Invalid or expired state received: {}", state);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Invalid or expired authorization state. Please try again.");
        }

        String telegramId = telegramIdOpt.get();
        log.info("State validated for Telegram ID: {}", telegramId);

        try {
            // Call the transactional service method
            authProcessingService.processGithubCallback(code, telegramId);

            // Return success if service method completes without exception
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("GitHub account linked successfully! You can close this window.");

        } catch (AuthProcessingService.GithubAccountAlreadyLinkedException e) {
            log.warn("Conflict processing GitHub callback: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT) // 409 Conflict
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(e.getMessage()); // Use message from exception
        } catch (Exception e) {
            // Catch other potential exceptions from the service layer (e.g., API errors)
            log.error("Error processing GitHub callback for state {}: {}", state, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("An error occurred while processing the GitHub callback. Please try again later.");
        }
    }
}