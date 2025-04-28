package com.example.cognitivequery.service.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;


@Service
@RequiredArgsConstructor
@Slf4j
public class GithubApiService {

    private final WebClient webClient;
    private final ClientRegistrationRepository clientRegistrationRepository;

    @Value("${github.token.url:https://github.com/login/oauth/access_token}")
    private String githubTokenUrl;

    @Value("${github.user.info.url:https://api.github.com/user}")
    private String githubUserInfoUrl;


    public String exchangeCodeForToken(String code) {
        ClientRegistration clientRegistration = clientRegistrationRepository.findByRegistrationId("github");
        if (clientRegistration == null) {
            throw new IllegalStateException("GitHub client registration not found");
        }

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("client_id", clientRegistration.getClientId());
        formData.add("client_secret", clientRegistration.getClientSecret());
        formData.add("code", code);
        formData.add("redirect_uri", clientRegistration.getRedirectUri());

        log.debug("Exchanging code for token with URL: {}", githubTokenUrl);

        ParameterizedTypeReference<Map<String, String>> typeRef = new ParameterizedTypeReference<>() {
        };

        Map<String, String> response = webClient.post()
                .uri(githubTokenUrl)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .bodyToMono(typeRef)
                .block();

        if (response == null || response.containsKey("error")) {
            log.error("Error exchanging code for token: {}", response);
            throw new RuntimeException("Failed to exchange code for token: " + response);
        }

        String accessToken = response.get("access_token");
        if (accessToken == null) {
            log.error("Access token not found in GitHub response: {}", response);
            throw new RuntimeException("Access token not found in GitHub response.");
        }
        return accessToken;
    }

    public Map<String, Object> getUserInfo(String accessToken) {
        log.debug("Fetching user info from: {}", githubUserInfoUrl);
        ParameterizedTypeReference<Map<String, Object>> typeRef = new ParameterizedTypeReference<>() {
        };

        Map<String, Object> userInfo = webClient.get()
                .uri(githubUserInfoUrl)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(typeRef)
                .block();

        if (userInfo == null) {
            log.error("Failed to fetch user info from GitHub.");
            throw new RuntimeException("Failed to fetch user info from GitHub.");
        }
        return userInfo;
    }

}