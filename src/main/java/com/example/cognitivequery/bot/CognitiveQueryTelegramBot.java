package com.example.cognitivequery.bot;

import com.example.cognitivequery.model.AppUser;
import com.example.cognitivequery.repository.UserRepository;
import com.example.cognitivequery.service.projectextractor.ProjectAnalyzerService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC; // Import MDC
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
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
    private final String backendApiBaseUrl;
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
        this.backendApiBaseUrl = backendApiBaseUrl;
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
            String telegramIdStr = String.valueOf(userId);

            MDC.put("telegramId", telegramIdStr); // Add user ID to MDC
            try {
                String messageText = message.getText();
                String userFirstName = message.getFrom().getFirstName();
                log.info("Received message. Chat ID: {}, Text: '{}'", chatId, messageText); // Log without user ID here

                AppUser appUser = findOrCreateUser(telegramIdStr); // Use helper method
                if (appUser == null) {
                    sendMessage(chatId, "Sorry, there was a problem accessing user data. Please try again later.");
                    return; // Stop if user cannot be processed
                }

                UserState currentState = userStates.getOrDefault(userId, UserState.IDLE);

                if (messageText.startsWith("/")) {
                    handleCommand(chatId, userId, telegramIdStr, appUser, messageText, userFirstName);
                } else if (currentState == UserState.WAITING_FOR_REPO_URL) {
                    handleRepoUrlInput(chatId, userId, appUser, messageText);
                } else {
                    log.debug("Ignoring non-command message in state {}", currentState);
                }
            } catch(Exception e) {
                log.error("Unhandled exception during update processing", e);
                sendMessage(chatId, "An unexpected error occurred.");
            }
            finally {
                MDC.remove("telegramId"); // Clean up MDC
            }
        }
    }

    // Helper to find or create user, handling DB errors
    private AppUser findOrCreateUser(String telegramIdStr) {
        try {
            return userRepository.findByTelegramId(telegramIdStr)
                    .orElseGet(() -> {
                        log.info("User not found, creating new.");
                        AppUser newUser = new AppUser(telegramIdStr);
                        return userRepository.save(newUser);
                    });
        } catch (Exception e) {
            log.error("Database error fetching/creating user.", e);
            return null; // Indicate error
        }
    }


    private void handleCommand(long chatId, long userId, String telegramIdStr, AppUser appUser, String command, String userFirstName) {
        // appUser should not be null here due to check in onUpdateReceived
        userStates.put(userId, UserState.IDLE);

        switch (command) {
            case "/start":
                sendMessage(chatId, "Hello, " + userFirstName + "! I'm CognitiveQuery bot.\n➡️ Use /connect_github to link your GitHub account.\n➡️ Use /analyze_repo to analyze a repository.");
                break;
            case "/connect_github":
                initiateGithubAuthFlow(chatId, telegramIdStr);
                break;
            case "/analyze_repo":
                startRepoAnalysisFlow(chatId, appUser);
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
        log.info("Initiating GitHub auth");
        sendMessage(chatId, "Requesting authorization URL from backend...");

        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("telegramId", telegramId);

        try {
            ParameterizedTypeReference<Map<String, String>> typeRef = new ParameterizedTypeReference<>() {};
            Map<String, String> response = webClient.post()
                    .uri("/api/auth/github/initiate")
                    .contentType(MediaType.APPLICATION_JSON) // Ensure content type is set
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .map(errorBody -> new RuntimeException("Backend API error: " + clientResponse.statusCode() + " Body: " + errorBody)))
                    .bodyToMono(typeRef)
                    .block();

            if (response != null && response.containsKey("authorizationUrl")) {
                String authUrl = response.get("authorizationUrl");
                log.info("Received authorization URL: {}", authUrl.substring(0, Math.min(authUrl.length(), 100)) + "..."); // Log truncated URL
                String reply = "Please click the link below to authorize with GitHub:\n\n" + authUrl + "\n\nAfter authorization, you can use /analyze_repo.";
                sendMessage(chatId, reply);
            } else {
                log.error("Failed to get authorization URL from backend (unexpected response format). Response: {}", response);
                sendMessage(chatId, "Sorry, I couldn't get the authorization link from the backend due to an unexpected response. Please try again later.");
            }
        } catch (Exception e) {
            log.error("Error calling backend API to initiate auth", e);
            sendMessage(chatId, "An error occurred while contacting the backend (" + e.getMessage() + "). Please try again later.");
        }
    }

    private void startRepoAnalysisFlow(long chatId, AppUser appUser) {
        if (appUser.getGithubId() == null || appUser.getGithubId().isEmpty()) {
            log.warn("User attempted analysis without linked GitHub account.");
            sendMessage(chatId, "You need to connect your GitHub account first. Use /connect_github.");
            return;
        }

        log.info("Starting analysis flow. Setting state to WAITING_FOR_REPO_URL.");
        userStates.put(Long.parseLong(appUser.getTelegramId()), UserState.WAITING_FOR_REPO_URL);
        sendMessage(chatId, "Please send me the full HTTPS URL of the public GitHub repository you want to analyze (e.g., https://github.com/owner/repo).");
    }

    private void handleRepoUrlInput(long chatId, long userId, AppUser appUser, String repoUrl) {
        log.info("Handling repo URL input: '{}'", repoUrl);
        userStates.remove(userId);

        Matcher matcher = GITHUB_URL_PATTERN.matcher(repoUrl.trim());
        if (!matcher.matches()) {
            log.warn("Invalid GitHub URL format received: {}", repoUrl);
            sendMessage(chatId, "The URL doesn't look like a valid GitHub repository URL (e.g., https://github.com/owner/repo). Please try /analyze_repo again.");
            return;
        }

        String validatedUrl = repoUrl.trim();

        sendMessage(chatId, "Received repository URL: " + validatedUrl + "\n⏳ Starting analysis... This may take a while.");

        // --- Run analysis in a separate thread to avoid blocking the bot ---
        //     (Simple example, consider using @Async or ExecutorService for production)
        Thread analysisThread = new Thread(() -> {
            MDC.put("telegramId", String.valueOf(userId)); // Propagate MDC to new thread
            try {
                boolean cleanupClone = true;
                Path resultSchemaPath = projectAnalyzerService.analyzeAndProcessProject(validatedUrl, cleanupClone);
                log.info("Analysis complete. Schema IR file path: {}", resultSchemaPath);

                try {
                    // Re-fetch user within this thread/transaction if needed, or pass ID
                    AppUser userToUpdate = userRepository.findById(appUser.getId())
                            .orElseThrow(() -> new RuntimeException("User not found for saving results"));

                    userToUpdate.setAnalysisResults(validatedUrl, resultSchemaPath.toString());
                    userRepository.save(userToUpdate);
                    log.info("Saved analysis results (schema path)");

                    sendMessage(chatId, "✅ Analysis successful!\nRepository: " + validatedUrl + "\nSchema representation saved at: " + resultSchemaPath.toString() + "\n(Note: This path is on the server)");

                } catch (Exception dbEx) {
                    log.error("Failed to save analysis results path to DB", dbEx);
                    sendMessage(chatId, "⚠️ Analysis was done (schema generated at " + resultSchemaPath.toString() + "), but I failed to save the path to your record.");
                }

            } catch (Exception analysisEx) {
                log.error("Analysis failed for URL {}", validatedUrl, analysisEx);
                String reason = analysisEx.getMessage();
                if (analysisEx.getCause() != null) {
                    reason += " (Cause: " + analysisEx.getCause().getMessage() + ")";
                }
                sendMessage(chatId, "❌ Analysis failed for repository: " + validatedUrl + "\nReason: " + reason);
            } finally {
                userStates.remove(userId); // Ensure state is cleared in this thread too
                MDC.remove("telegramId"); // Clean up MDC for this thread
            }
        });
        analysisThread.setName("AnalysisThread-" + userId);
        analysisThread.start();
        // --- End of async execution block ---
    }


    private void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        try {
            execute(message);
            log.debug("Sent message to chat ID {}", chatId);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chat ID {}: {}", chatId, e.getMessage());
        }
    }

    private enum UserState {
        IDLE,
        WAITING_FOR_REPO_URL
    }
}