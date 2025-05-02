package com.example.cognitivequery.bot;

import com.example.cognitivequery.model.AppUser;
import com.example.cognitivequery.repository.UserRepository;
import com.example.cognitivequery.service.llm.GeminiService;
import com.example.cognitivequery.service.projectextractor.GitInfoService;
import com.example.cognitivequery.service.projectextractor.ProjectAnalyzerService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class CognitiveQueryTelegramBot extends TelegramLongPollingBot {

    // Injected dependencies
    private final String botUsername;
    private final UserRepository userRepository;
    private final ProjectAnalyzerService projectAnalyzerService;
    private final WebClient webClient; // Configured with backend base URL
    private final GitInfoService gitInfoService;
    private final GeminiService geminiService;

    // Simple in-memory state management per user
    private final Map<Long, UserState> userStates = new ConcurrentHashMap<>();

    // Regex to validate GitHub URL
    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile(
            "^(https?://)?(www\\.)?github\\.com/[\\w.-]+/[\\w.-]+(/)?(?:\\.git)?/?$", Pattern.CASE_INSENSITIVE);


    public CognitiveQueryTelegramBot(
            @Value("${telegram.bot.token}") String botToken,
            @Value("${telegram.bot.username}") String botUsername,
            @Value("${backend.api.base-url}") String backendApiBaseUrl,
            UserRepository userRepository,
            ProjectAnalyzerService projectAnalyzerService,
            GitInfoService gitInfoService,
            GeminiService geminiService,
            WebClient.Builder webClientBuilder
    ) {
        super(botToken);
        this.botUsername = botUsername;
        this.userRepository = userRepository;
        this.projectAnalyzerService = projectAnalyzerService;
        this.gitInfoService = gitInfoService;
        this.geminiService = geminiService;
        this.webClient = webClientBuilder.baseUrl(backendApiBaseUrl).build();
        log.info("Telegram Bot initialized. Username: {}, Backend API: {}", botUsername, backendApiBaseUrl);
    }

    // Register the bot with Telegram API and set command menu on startup
    @PostConstruct
    public void registerBotAndSetCommands() {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(this);
            log.info("Telegram Bot registered successfully!");
            setBotCommands(); // Set the commands menu
        } catch (TelegramApiException e) {
            log.error("Error registering Telegram Bot or setting commands", e);
        }
    }

    // Sets the command menu in Telegram
    private void setBotCommands() {
        List<BotCommand> commands = new ArrayList<>();
        commands.add(new BotCommand("start", "Start interacting with the bot"));
        commands.add(new BotCommand("connect_github", "Link your GitHub account"));
        commands.add(new BotCommand("analyze_repo", "Analyze a repository schema"));
        commands.add(new BotCommand("query", "Ask a question about analyzed data"));
        commands.add(new BotCommand("help", "Get help and see available commands"));

        try {
            this.execute(new SetMyCommands(commands, new BotCommandScopeDefault(), null));
            log.info("Successfully set bot commands menu.");
        } catch (TelegramApiException e) {
            log.error("Failed to set bot commands menu", e);
        }
    }

    @Override
    public String getBotUsername() {
        return this.botUsername;
    }

    // Main method for handling incoming updates from Telegram
    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            Message message = update.getMessage();
            long chatId = message.getChatId();
            long userId = message.getFrom().getId();
            String telegramIdStr = String.valueOf(userId);

            // Add user ID to Mapped Diagnostic Context for logging correlation
            MDC.put("telegramId", telegramIdStr);
            try {
                String messageText = message.getText();
                String userFirstName = message.getFrom().getFirstName();
                log.info("Received message. Chat ID: {}, Text: '{}'", chatId, messageText);

                // Ensure user exists in our database
                AppUser appUser = findOrCreateUser(telegramIdStr);
                if (appUser == null) {
                    sendMessage(chatId, "Sorry, there was a problem accessing user data. Please try again later.");
                    return;
                }

                UserState currentState = userStates.getOrDefault(userId, UserState.IDLE);

                // Route message based on command or current user state
                if (messageText.toLowerCase().startsWith("/query ")) {
                    String queryText = messageText.substring(7).trim();
                    if (!queryText.isEmpty()) {
                        handleQueryCommand(chatId, appUser, queryText);
                    } else {
                        sendMessage(chatId, "Please provide your query after the /query command.\nExample: `/query show all tasks`");
                        userStates.put(userId, UserState.WAITING_FOR_LLM_QUERY);
                        sendMessage(chatId, "Alternatively, just type your query now.");
                    }
                }
                else if (currentState == UserState.WAITING_FOR_LLM_QUERY) {
                    handleQueryInput(chatId, userId, appUser, messageText);
                }
                else if (messageText.startsWith("/")) {
                    handleCommand(chatId, userId, telegramIdStr, appUser, messageText, userFirstName);
                }
                else if (currentState == UserState.WAITING_FOR_REPO_URL) {
                    handleRepoUrlInput(chatId, userId, appUser, messageText);
                }
                else {
                    log.debug("Ignoring non-command message in state {}", currentState);
                    sendMessage(chatId, "I'm not sure what to do with that. Try /help for available commands.");
                }
            } catch(Exception e) {
                log.error("Unhandled exception during update processing", e);
                sendMessage(chatId, "An unexpected error occurred while processing your message.");
            }
            finally {
                // Always clean up MDC for the current thread
                MDC.remove("telegramId");
            }
        }
    }

    // Retrieves user from DB or creates a new one if not found
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
            return null;
        }
    }

    // Handles standard bot commands like /start, /help, etc.
    private void handleCommand(long chatId, long userId, String telegramIdStr, AppUser appUser, String command, String userFirstName) {
        if (!command.startsWith("/query")) {
            userStates.put(userId, UserState.IDLE); // Reset state for most commands
        }

        if (appUser == null && command.equals("/analyze_repo")) {
            log.warn("Cannot execute command {} because user data is unavailable", command);
            sendMessage(chatId, "Cannot process command due to a temporary issue accessing user data.");
            return;
        }

        switch (command) {
            case "/start":
                sendMessage(chatId, "Hello, " + userFirstName + "! I'm CognitiveQuery bot.\n➡️ Use /connect_github to link your GitHub account.\n➡️ Use /analyze_repo to analyze a repository.\n➡️ Use /query <your question> after analysis to ask about the data.");
                break;
            case "/connect_github":
                initiateGithubAuthFlow(chatId, telegramIdStr);
                break;
            case "/analyze_repo":
                startRepoAnalysisFlow(chatId, appUser);
                break;
            case "/help":
                sendMessage(chatId, "Available commands:\n" +
                        "/connect_github - Link your GitHub account\n" +
                        "/analyze_repo - Analyze a GitHub repository to generate its schema\n" +
                        "/query <your question> - Ask about the data in your last analyzed repository\n" +
                        "/help - Show this message");
                break;
            default:
                if (!command.startsWith("/query")) {
                    sendMessage(chatId, "Sorry, I don't understand that command. Try /help.");
                }
                break;
        }
    }

    // Calls the backend to get the GitHub OAuth URL
    private void initiateGithubAuthFlow(long chatId, String telegramId) {
        log.info("Initiating GitHub auth");
        sendMessage(chatId, "Requesting authorization URL from backend...");
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("telegramId", telegramId);
        try {
            ParameterizedTypeReference<Map<String, String>> typeRef = new ParameterizedTypeReference<>() {};
            Map<String, String> response = webClient.post() // Uses WebClient configured with backend base URL
                    .uri("/api/auth/github/initiate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .map(errorBody -> new RuntimeException("Backend API error: " + clientResponse.statusCode() + " Body: " + errorBody)))
                    .bodyToMono(typeRef)
                    .block();

            if (response != null && response.containsKey("authorizationUrl")) {
                String authUrl = response.get("authorizationUrl");
                log.info("Received authorization URL: {}...", authUrl.substring(0, Math.min(authUrl.length(), 100)));
                String reply = "Please click the link below to authorize with GitHub:\n\n" + authUrl + "\n\nAfter authorization, you can use /analyze_repo.";
                sendMessage(chatId, reply);
            } else {
                log.error("Failed to get authorization URL from backend (unexpected response format). Response: {}", response);
                sendMessage(chatId, "Sorry, I couldn't get the authorization link. Unexpected response from backend.");
            }
        } catch (Exception e) {
            log.error("Error calling backend API to initiate auth", e);
            sendMessage(chatId, "An error occurred while contacting the backend (" + e.getMessage() + ").");
        }
    }

    // Checks GitHub link and prompts for repository URL
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

    // Handles the repository URL input, checks cache, and triggers analysis if needed
    private void handleRepoUrlInput(long chatId, long userId, AppUser appUser, String repoUrl) {
        log.info("Handling repo URL input: '{}'", repoUrl);
        userStates.remove(userId); // Reset state

        Matcher matcher = GITHUB_URL_PATTERN.matcher(repoUrl.trim());
        if (!matcher.matches()) {
            log.warn("Invalid GitHub URL format received: {}", repoUrl);
            sendMessage(chatId, "The URL doesn't look like a valid GitHub repository URL (e.g., https://github.com/owner/repo). Please try /analyze_repo again.");
            return;
        }

        String validatedUrl = repoUrl.trim();
        final String userTelegramId = appUser.getTelegramId();

        sendMessage(chatId, "Checking repository status...");
        Optional<String> currentCommitHashOpt = gitInfoService.getRemoteHeadCommitHash(validatedUrl);

        if (currentCommitHashOpt.isEmpty()) {
            log.warn("Could not get current commit hash for {}. Proceeding with analysis.", validatedUrl);
            performAnalysis(chatId, userId, appUser, validatedUrl, "UNKNOWN"); // Pass placeholder hash
            return;
        }

        String currentCommitHash = currentCommitHashOpt.get();
        log.info("Current commit hash for {}: {}", validatedUrl, currentCommitHash);

        boolean needsAnalysis = true;
        String reason = "";
        String existingSchemaPathStr = appUser.getProcessedSchemaPath();

        // Check if URL, hash match and if the schema file still exists
        if (validatedUrl.equals(appUser.getLastAnalyzedRepoUrl()) && currentCommitHash.equals(appUser.getLastAnalyzedCommitHash())) {
            log.debug("Repository URL and Commit Hash match the last analysis.");
            if (existingSchemaPathStr != null && !existingSchemaPathStr.isBlank()) {
                try {
                    Path existingSchemaPath = Paths.get(existingSchemaPathStr);
                    if (Files.exists(existingSchemaPath) && Files.isRegularFile(existingSchemaPath)) {
                        log.info("Existing schema file found and is valid: {}", existingSchemaPath);
                        needsAnalysis = false; // Analysis not needed, use existing file
                        sendMessage(chatId, "✅ This repository version was already analyzed.\nYou can now ask questions using /query <your question>.");
                    } else {
                        log.warn("Schema file path found in DB, but file does not exist or is not a regular file: {}", existingSchemaPath);
                        reason = "Previous analysis result file is missing."; // Need re-analysis
                    }
                } catch (InvalidPathException e) {
                    log.error("Invalid schema file path stored in DB: {}", existingSchemaPathStr, e);
                    reason = "Invalid path stored for previous analysis result."; // Need re-analysis
                }
            } else {
                log.warn("Commit hash matches, but no schema path stored in DB");
                reason = "Metadata indicates previous analysis, but result path is missing."; // Need re-analysis
            }
        } else {
            log.info("Repository URL or commit hash differs.");
            reason = "Repository has been updated or this is a new URL."; // Need re-analysis
        }

        if (needsAnalysis) {
            log.info("Proceeding with analysis. Reason: {}", reason.isEmpty() ? "Initial analysis or mismatch" : reason);
            performAnalysis(chatId, userId, appUser, validatedUrl, currentCommitHash); // Pass the current hash
        }
    }

    // Performs the project analysis in a background thread
    private void performAnalysis(long chatId, long userId, AppUser appUser, String validatedUrl, String commitHashToSave) {
        sendMessage(chatId, "⏳ Starting analysis for " + validatedUrl + (commitHashToSave.equals("UNKNOWN") ? "" : " (version: " + commitHashToSave.substring(0, 7) + ")") + "... This may take a while.");

        Thread analysisThread = new Thread(() -> {
            MDC.put("telegramId", appUser.getTelegramId()); // Propagate logging context
            Path resultSchemaPath = null;
            try {
                boolean cleanupClone = true; // Delete cloned repo after analysis
                // Call the service to clone, parse, and save the schema JSON
                resultSchemaPath = projectAnalyzerService.analyzeAndProcessProject(validatedUrl, appUser.getTelegramId(), cleanupClone);
                log.info("Analysis thread complete. Schema IR file path: {}", resultSchemaPath);

                try {
                    // Save the results (URL, schema path, commit hash) to the user's record
                    AppUser userToUpdate = userRepository.findById(appUser.getId())
                            .orElseThrow(() -> new RuntimeException("User not found for saving results"));
                    userToUpdate.setAnalysisResults(validatedUrl, resultSchemaPath.toString(), commitHashToSave);
                    userRepository.save(userToUpdate);
                    log.info("Saved analysis results (schema path and commit hash)");

                    sendMessage(chatId, "✅ Analysis successful! Schema saved.\nYou can now ask questions using `/query <your question>`");

                } catch (Exception dbEx) {
                    log.error("Failed to save analysis results path/hash to DB", dbEx);
                    sendMessage(chatId, "⚠️ Analysis was done (schema generated at " + resultSchemaPath + "), but I failed to save the results metadata.");
                }

            } catch (Exception analysisEx) {
                log.error("Analysis failed for URL {}", validatedUrl, analysisEx);
                String reason = analysisEx.getMessage();
                if (analysisEx.getCause() != null) {
                    reason += " (Cause: " + analysisEx.getCause().getMessage() + ")";
                }
                sendMessage(chatId, "❌ Analysis failed for repository: " + validatedUrl + "\nReason: " + reason);
            } finally {
                MDC.remove("telegramId"); // Clean up logging context for this thread
            }
        });
        analysisThread.setName("AnalysisThread-" + userId);
        analysisThread.start(); // Start the background thread
    }

    // Handles the /query command with provided text
    private void handleQueryCommand(long chatId, AppUser appUser, String queryText) {
        log.info("Received /query command with text: '{}'", queryText);
        processUserQuery(chatId, appUser, queryText);
    }

    // Handles text input when expecting a query
    private void handleQueryInput(long chatId, long userId, AppUser appUser, String queryText) {
        log.info("Received query text while waiting: '{}'", queryText);
        userStates.remove(userId); // Reset state
        processUserQuery(chatId, appUser, queryText);
    }

    // Reads the schema, sends the query to the LLM, and replies with the result
    private void processUserQuery(long chatId, AppUser appUser, String userQuery) {
        String schemaPathStr = appUser.getProcessedSchemaPath();
        if (schemaPathStr == null || schemaPathStr.isBlank()) {
            sendMessage(chatId, "You haven't analyzed any repository yet, or the last analysis failed to save. Please use /analyze_repo first.");
            return;
        }

        Path schemaPath;
        String schemaJson;
        try {
            // Read the schema JSON file content
            schemaPath = Paths.get(schemaPathStr);
            if (!Files.exists(schemaPath) || !Files.isRegularFile(schemaPath)) {
                log.error("Schema file not found or invalid: {}", schemaPathStr);
                sendMessage(chatId, "Error: The previously analyzed schema file is missing. Please run /analyze_repo again for: " + appUser.getLastAnalyzedRepoUrl());
                return;
            }
            schemaJson = Files.readString(schemaPath, StandardCharsets.UTF_8);
            log.debug("Read schema file content ({} bytes) from {}", schemaJson.length(), schemaPath);

        } catch (Exception e) { // Catch potential IO/Path errors
            log.error("Error accessing schema file: {}", schemaPathStr, e);
            sendMessage(chatId, "An error occurred while accessing the schema file ("+ e.getClass().getSimpleName() +"). Please try re-analyzing or contact support.");
            return;
        }

        sendMessage(chatId, "🧠 Got it! Asking the AI based on the schema for " + appUser.getLastAnalyzedRepoUrl() + "...");

        // Call the LLM service in a background thread
        Thread llmThread = new Thread(() -> {
            MDC.put("telegramId", appUser.getTelegramId());
            try {
                Optional<String> generatedSqlOpt = geminiService.generateSqlFromSchema(schemaJson, userQuery);

                if (generatedSqlOpt.isPresent()) {
                    String sql = generatedSqlOpt.get();
                    log.info("Generated SQL received.");
                    sendMessage(chatId, "🤖 Generated SQL query:\n\n`" + sql + "`\n\n" +
                            "**Disclaimer:** Review this query before execution.");
                } else {
                    log.warn("Gemini service did not return SQL for query: '{}'", userQuery);
                    sendMessage(chatId, "Sorry, I couldn't generate an SQL query. The AI might have had trouble understanding the request or the schema. Please try rephrasing.");
                }
            } catch (Exception e) {
                log.error("Error during Gemini SQL generation for query: '{}'", userQuery, e);
                sendMessage(chatId, "An error occurred while generating the SQL query.");
            } finally {
                MDC.remove("telegramId");
            }
        });
        llmThread.setName("LLMQueryThread-" + appUser.getTelegramId());
        llmThread.start();
    }

    // Utility method to send a message to a chat
    private void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setParseMode("Markdown"); // Enable Markdown for formatting
        try {
            execute(message);
            log.debug("Sent message to chat ID {}", chatId);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chat ID {}: {}", chatId, e.getMessage());
        }
    }

    // Enum to manage simple user states for multi-step interactions
    private enum UserState {
        IDLE, // Default state
        WAITING_FOR_REPO_URL, // Waiting for user to send GitHub repo URL
        WAITING_FOR_LLM_QUERY // Waiting for user to send natural language query
    }
}