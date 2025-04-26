package com.example.cognitivequery.bot;

import com.example.cognitivequery.model.AppUser;
import com.example.cognitivequery.repository.UserRepository;
import com.example.cognitivequery.service.projectextractor.ProjectAnalyzerService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference; // Needed for WebClient response parsing
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class CognitiveQueryTelegramBot extends TelegramLongPollingBot {

    private final String botUsername;
    private final UserRepository userRepository;
    private final ProjectAnalyzerService projectAnalyzerService;
    private final WebClient webClient;

    private final Map<Long, UserState> userStates = new ConcurrentHashMap<>();

    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile(
            "^(https?://)?(www\\.)?github\\.com/[\\w.-]+/[\\w.-]+(/)?(?:\\.git)?/?$", Pattern.CASE_INSENSITIVE);


    public CognitiveQueryTelegramBot(
            @Value("${telegram.bot.token}") String botToken,
            @Value("${telegram.bot.username}") String botUsername,
            @Value("${backend.api.base-url}") String backendApiBaseUrl,
            UserRepository userRepository,
            ProjectAnalyzerService projectAnalyzerService,
            WebClient.Builder webClientBuilder
    ) {
        super(botToken);
        this.botUsername = botUsername;
        this.userRepository = userRepository;
        this.projectAnalyzerService = projectAnalyzerService;
        this.webClient = webClientBuilder.baseUrl(backendApiBaseUrl).build();
        log.info("Telegram Bot initialized. Username: {}, Token: ***, Backend API: {}", botUsername, backendApiBaseUrl);
    }

    @PostConstruct
    public void registerBot() {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(this);
            log.info("Telegram Bot registered successfully!");
        } catch (TelegramApiException e) {
            log.error("Error registering Telegram Bot", e);
        }
    }

    @Override
    public String getBotUsername() {
        return this.botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            Message message = update.getMessage();
            long chatId = message.getChatId();
            long userId = message.getFrom().getId();
            String messageText = message.getText();
            String userFirstName = message.getFrom().getFirstName();

            log.info("Received message from user ID: {}, chat ID: {}, text: '{}'", userId, chatId, messageText);

            String telegramIdStr = String.valueOf(userId);
            // Wrap DB operation in try-catch as it might fail
            AppUser appUser;
            try {
                appUser = userRepository.findByTelegramId(telegramIdStr)
                        .orElseGet(() -> {
                            log.info("User with Telegram ID {} not found, creating new.", telegramIdStr);
                            AppUser newUser = new AppUser(telegramIdStr);
                            return userRepository.save(newUser);
                        });
            } catch (Exception e) {
                log.error("Database error fetching/creating user for Telegram ID {}", telegramIdStr, e);
                sendMessage(chatId, "Sorry, there was a problem accessing user data. Please try again later.");
                return; // Stop processing if user data is unavailable
            }

            UserState currentState = userStates.getOrDefault(userId, UserState.IDLE);

            if (messageText.startsWith("/")) {
                // Pass the potentially null appUser if DB failed, handle inside command if needed
                handleCommand(chatId, userId, telegramIdStr, appUser, messageText, userFirstName);
            } else if (currentState == UserState.WAITING_FOR_REPO_URL && appUser != null) {
                // Only handle repo URL if user exists and is in the correct state
                handleRepoUrlInput(chatId, userId, appUser, messageText);
            } else {
                log.debug("Ignoring non-command message from user {} in state {}", userId, currentState);
                // Optionally send a generic help message or ignore
                // sendMessage(chatId, "I'm not sure what you mean. Try /help for commands.");
            }
        }
    }

    private void handleCommand(long chatId, long userId, String telegramIdStr, AppUser appUser, String command, String userFirstName) {
        // Check if appUser is null (DB error case) for commands requiring user data
        if (appUser == null && (command.equals("/analyze_repo") /* || other commands needing user */) ) {
            log.warn("Cannot execute command {} because user data is unavailable for Telegram ID {}", command, telegramIdStr);
            sendMessage(chatId, "Cannot process command due to a temporary issue accessing user data.");
            return;
        }

        userStates.put(userId, UserState.IDLE); // Reset state

        switch (command) {
            case "/start":
                sendMessage(chatId, "Hello, " + userFirstName + "! I'm CognitiveQuery bot.\n➡️ Use /connect_github to link your GitHub account.\n➡️ Use /analyze_repo to analyze a repository.");
                break;
            case "/connect_github":
                initiateGithubAuthFlow(chatId, telegramIdStr);
                break;
            case "/analyze_repo":
                startRepoAnalysisFlow(chatId, appUser); // appUser is guaranteed not null here
                break;
            case "/help":
                sendMessage(chatId, "Available commands:\n/connect_github - Link your GitHub account\n/analyze_repo - Start analysis of a GitHub repository\n/help - Show this message");
                break;
            default:
                sendMessage(chatId, "Sorry, I don't understand that command. Try /help.");
                break;
        }
    }

    private void initiateGithubAuthFlow(long chatId, String telegramId) {
        log.info("Initiating GitHub auth for Telegram ID: {}", telegramId);
        sendMessage(chatId, "Requesting authorization URL from backend...");

        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("telegramId", telegramId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(requestBody, headers);

        try {
            ParameterizedTypeReference<Map<String, String>> typeRef = new ParameterizedTypeReference<>() {};
            Map<String, String> response = webClient.post()
                    .uri("/api/auth/github/initiate")
                    .bodyValue(requestBody)
                    .retrieve()
                    // Add specific error handling for HTTP status codes
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .map(errorBody -> new RuntimeException("Backend API error: " + clientResponse.statusCode() + " Body: " + errorBody)))
                    .bodyToMono(typeRef)
                    .block(); // Consider async/await or reactive chains in production

            if (response != null && response.containsKey("authorizationUrl")) {
                String authUrl = response.get("authorizationUrl");
                log.info("Received authorization URL: {}", authUrl);
                String reply = "Please click the link below to authorize with GitHub:\n\n" + authUrl + "\n\nAfter authorization, you can use /analyze_repo.";
                sendMessage(chatId, reply);
            } else {
                log.error("Failed to get authorization URL from backend (unexpected response format). Response: {}", response);
                sendMessage(chatId, "Sorry, I couldn't get the authorization link from the backend due to an unexpected response. Please try again later.");
            }
        } catch (Exception e) {
            log.error("Error calling backend API to initiate auth for Telegram ID {}", telegramId, e);
            sendMessage(chatId, "An error occurred while contacting the backend (" + e.getMessage() + "). Please try again later.");
        }
    }

    private void startRepoAnalysisFlow(long chatId, AppUser appUser) {
        // Check if user has linked GitHub account
        // This check is done before calling, but double-check just in case
        if (appUser == null || appUser.getGithubId() == null || appUser.getGithubId().isEmpty()) {
            log.warn("User (TG ID {}) attempted analysis without linked GitHub account or user data unavailable.",
                    appUser != null ? appUser.getTelegramId() : "UNKNOWN");
            sendMessage(chatId, "You need to connect your GitHub account first. Use /connect_github.");
            return;
        }

        log.info("Starting analysis flow for user {} (TG ID {}). Setting state to WAITING_FOR_REPO_URL.", appUser.getId(), appUser.getTelegramId());
        userStates.put(Long.parseLong(appUser.getTelegramId()), UserState.WAITING_FOR_REPO_URL);
        sendMessage(chatId, "Please send me the full HTTPS URL of the public GitHub repository you want to analyze (e.g., https://github.com/owner/repo).");
    }

    private void handleRepoUrlInput(long chatId, long userId, AppUser appUser, String repoUrl) {
        log.info("Handling repo URL input '{}' from user {} (TG ID {})", repoUrl, appUser.getId(), appUser.getTelegramId());
        userStates.remove(userId); // Reset state

        Matcher matcher = GITHUB_URL_PATTERN.matcher(repoUrl.trim());
        if (!matcher.matches()) {
            log.warn("Invalid GitHub URL format received: {}", repoUrl);
            sendMessage(chatId, "The URL doesn't look like a valid GitHub repository URL (e.g., https://github.com/owner/repo). Please try /analyze_repo again.");
            return;
        }

        String validatedUrl = repoUrl.trim();

        sendMessage(chatId, "Received repository URL: " + validatedUrl + "\n⏳ Starting analysis... This may take a while.");

        try {
            boolean cleanupClone = true; // Keep clone cleanup enabled
            Path resultPath = projectAnalyzerService.analyzeAndProcessProject(validatedUrl, cleanupClone);

            log.info("Analysis complete for user {} (TG ID {}). Result path: {}", appUser.getId(), appUser.getTelegramId(), resultPath);

            try {
                // Use a transactional method or re-fetch user within transaction if needed
                // For simplicity here, assume direct update is okay, but a service method is better
                appUser.setAnalysisResults(validatedUrl, resultPath.toString());
                userRepository.save(appUser); // Save the updated user
                log.info("Saved analysis results for user {} (TG ID {})", appUser.getId(), appUser.getTelegramId());

                sendMessage(chatId, "✅ Analysis successful!\nRepository: " + validatedUrl + "\nProcessed entity files stored at: " + resultPath.toString() + "\n(Note: This path is on the server)");

            } catch (Exception dbEx) {
                log.error("Failed to save analysis results to DB for user {} (TG ID {})", appUser.getId(), appUser.getTelegramId(), dbEx);
                sendMessage(chatId, "⚠️ Analysis was done, but I failed to save the results to your record. The results are at " + resultPath.toString() + " on the server.");
            }

        } catch (Exception analysisEx) {
            log.error("Analysis failed for URL {} requested by user {} (TG ID {})", validatedUrl, appUser.getId(), appUser.getTelegramId(), analysisEx);
            // Provide more context from the exception if possible
            String reason = analysisEx.getMessage();
            if (analysisEx.getCause() != null) {
                reason += " (Cause: " + analysisEx.getCause().getMessage() + ")";
            }
            sendMessage(chatId, "❌ Analysis failed for repository: " + validatedUrl + "\nReason: " + reason);
        } finally {
            userStates.remove(userId);
        }
    }

    private void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        // Optional: Add parseMode if you want to use Markdown or HTML
        // message.setParseMode(ParseMode.MARKDOWN);
        try {
            execute(message);
            log.debug("Sent message to chat ID {}", chatId);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chat ID {}: {}", chatId, e.getMessage());
            // Consider alternative notification or retry logic if sending is critical
        }
    }

    private enum UserState {
        IDLE,
        WAITING_FOR_REPO_URL
    }
}