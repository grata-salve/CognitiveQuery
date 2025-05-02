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

    private final String botUsername;
    private final UserRepository userRepository;
    private final ProjectAnalyzerService projectAnalyzerService;
    private final WebClient webClient;
    private final GitInfoService gitInfoService;
    private final GeminiService geminiService;

    private final Map<Long, UserState> userStates = new ConcurrentHashMap<>();

    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile(
            "^(https?://)?(www\\.)?github\\.com/[\\w.-]+/[\\w.-]+(/)?(?:\\.git)?/?$", Pattern.CASE_INSENSITIVE);

    // Pattern for MarkdownV2 escaping
    private static final Pattern ESCAPE_PATTERN = Pattern.compile("([_*~`>\\[\\]()#\\+\\-=|{}.!])");


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

    @PostConstruct
    public void registerBotAndSetCommands() {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(this);
            log.info("Telegram Bot registered successfully!");
            setBotCommands();
        } catch (TelegramApiException e) {
            log.error("Error registering Telegram Bot or setting commands", e);
        }
    }

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

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            Message message = update.getMessage();
            long chatId = message.getChatId();
            long userId = message.getFrom().getId();
            String telegramIdStr = String.valueOf(userId);

            MDC.put("telegramId", telegramIdStr);
            try {
                String messageText = message.getText();
                String userFirstName = message.getFrom().getFirstName();
                log.info("Received message. Chat ID: {}, Text: '{}'", chatId, messageText);

                AppUser appUser = findOrCreateUser(telegramIdStr);
                if (appUser == null) {
                    sendMessage(chatId, "Sorry, there was a problem accessing user data\\. Please try again later\\."); // Escaped
                    return;
                }

                UserState currentState = userStates.getOrDefault(userId, UserState.IDLE);

                if (messageText.toLowerCase().startsWith("/query ")) {
                    String queryText = messageText.substring(7).trim();
                    if (!queryText.isEmpty()) {
                        handleQueryCommand(chatId, appUser, queryText);
                    } else {
                        sendMessage(chatId, "Please provide your query after the `/query` command\\.\nExample: `/query show all tasks`");
                        userStates.put(userId, UserState.WAITING_FOR_LLM_QUERY);
                        sendMessage(chatId, "Alternatively, just type your query now\\.");
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
                    sendMessage(chatId, "I'm not sure what to do with that\\. Try `/help` for available commands\\.");
                }
            } catch(Exception e) {
                log.error("Unhandled exception during update processing", e);
                sendMessage(chatId, "An unexpected error occurred while processing your message\\.");
            }
            finally {
                MDC.remove("telegramId");
            }
        }
    }

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

    private void handleCommand(long chatId, long userId, String telegramIdStr, AppUser appUser, String command, String userFirstName) {
        if (!command.startsWith("/query")) {
            userStates.put(userId, UserState.IDLE);
        }

        if (appUser == null && command.equals("/analyze_repo")) {
            log.warn("Cannot execute command {} because user data is unavailable", command);
            sendMessage(chatId, "Cannot process command due to a temporary issue accessing user data\\.");
            return;
        }

        switch (command) {
            case "/start":
                sendMessage(chatId, "Hello, " + escapeMarkdownV2(userFirstName) + "\\! I'm CognitiveQuery bot\\.\n➡️ Use `/connect_github` to link your GitHub account\\.\n➡️ Use `/analyze_repo` to analyze a repository\\.\n➡️ Use `/query <your question>` after analysis to ask about the data\\.");
                break;
            case "/connect_github":
                initiateGithubAuthFlow(chatId, telegramIdStr);
                break;
            case "/analyze_repo":
                startRepoAnalysisFlow(chatId, appUser);
                break;
            case "/help":
                sendMessage(chatId, "Available commands:\n" +
                        "`/connect_github` \\- Link your GitHub account\n" +
                        "`/analyze_repo` \\- Analyze a GitHub repository to generate its schema\n" +
                        "`/query <your question>` \\- Ask about the data in your last analyzed repository\n" +
                        "`/help` \\- Show this message");
                break;
            default:
                if (!command.startsWith("/query")) {
                    sendMessage(chatId, "Sorry, I don't understand that command\\. Try `/help`\\.");
                }
                break;
        }
    }

    private void initiateGithubAuthFlow(long chatId, String telegramId) {
        log.info("Initiating GitHub auth");
        sendMessage(chatId, "Requesting authorization URL from backend\\.\\.\\."); // Escaped
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("telegramId", telegramId);
        try {
            ParameterizedTypeReference<Map<String, String>> typeRef = new ParameterizedTypeReference<>() {};
            Map<String, String> response = webClient.post()
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
                // URL itself doesn't need escaping for MarkdownV2 link context
                String reply = "Please click the link below to authorize with GitHub:\n\n" + authUrl + "\n\nAfter authorization, you can use `/analyze_repo`.";
                sendMessage(chatId, reply);
            } else {
                log.error("Failed to get authorization URL from backend (unexpected response format). Response: {}", response);
                sendMessage(chatId, "Sorry, I couldn't get the authorization link\\. Unexpected response from backend\\.");
            }
        } catch (Exception e) {
            log.error("Error calling backend API to initiate auth", e);
            sendMessage(chatId, "An error occurred while contacting the backend \\(" + escapeMarkdownV2(e.getMessage()) + "\\)\\."); // Escape error message and dots/parens
        }
    }

    private void startRepoAnalysisFlow(long chatId, AppUser appUser) {
        if (appUser.getGithubId() == null || appUser.getGithubId().isEmpty()) {
            log.warn("User attempted analysis without linked GitHub account.");
            sendMessage(chatId, "You need to connect your GitHub account first\\. Use `/connect_github`\\.");
            return;
        }
        log.info("Starting analysis flow. Setting state to WAITING_FOR_REPO_URL.");
        userStates.put(Long.parseLong(appUser.getTelegramId()), UserState.WAITING_FOR_REPO_URL);
        sendMessage(chatId, "Please send me the full HTTPS URL of the public GitHub repository you want to analyze \\(e\\.g\\., `https://github.com/owner/repo`\\)\\."); // Escape .,(),`
    }

    private void handleRepoUrlInput(long chatId, long userId, AppUser appUser, String repoUrl) {
        log.info("Handling repo URL input: '{}'", repoUrl);
        userStates.remove(userId);

        Matcher matcher = GITHUB_URL_PATTERN.matcher(repoUrl.trim());
        if (!matcher.matches()) {
            log.warn("Invalid GitHub URL format received: {}", repoUrl);
            sendMessage(chatId, "The URL doesn't look like a valid GitHub repository URL \\(e\\.g\\., `https://github.com/owner/repo`\\)\\. Please try `/analyze_repo` again\\.");
            return;
        }

        String validatedUrl = repoUrl.trim();
        final String userTelegramId = appUser.getTelegramId();

        sendMessage(chatId, "Checking repository status\\.\\.\\.");
        Optional<String> currentCommitHashOpt = gitInfoService.getRemoteHeadCommitHash(validatedUrl);

        if (currentCommitHashOpt.isEmpty()) {
            log.warn("Could not get current commit hash for {}. Proceeding with analysis.", validatedUrl);
            performAnalysis(chatId, userId, appUser, validatedUrl, "UNKNOWN");
            return;
        }

        String currentCommitHash = currentCommitHashOpt.get();
        log.info("Current commit hash for {}: {}", validatedUrl, currentCommitHash);

        boolean needsAnalysis = true;
        String reason = "";
        String existingSchemaPathStr = appUser.getProcessedSchemaPath();
        String escapedLastRepoUrl = escapeMarkdownV2(appUser.getLastAnalyzedRepoUrl());

        if (validatedUrl.equals(appUser.getLastAnalyzedRepoUrl()) && currentCommitHash.equals(appUser.getLastAnalyzedCommitHash())) {
            log.debug("Repository URL and Commit Hash match the last analysis.");
            if (existingSchemaPathStr != null && !existingSchemaPathStr.isBlank()) {
                try {
                    Path existingSchemaPath = Paths.get(existingSchemaPathStr);
                    if (Files.exists(existingSchemaPath) && Files.isRegularFile(existingSchemaPath)) {
                        log.info("Existing schema file found and is valid: {}", existingSchemaPath);
                        needsAnalysis = false;
                        String escapedPath = escapeMarkdownV2(existingSchemaPath.toString());
                        sendMessage(chatId, "✅ This repository version \\(" + escapedLastRepoUrl + "\\) was already analyzed\\.\nSchema is available at: `" + escapedPath + "`\n\\(Note: This path is on the server\\)\nYou can now ask questions using `/query <your question>`\\.");
                    } else {
                        log.warn("Schema file path found in DB, but file does not exist or is not a regular file: {}", existingSchemaPath);
                        reason = "Previous analysis result file is missing.";
                    }
                } catch (InvalidPathException e) {
                    log.error("Invalid schema file path stored in DB: {}", existingSchemaPathStr, e);
                    reason = "Invalid path stored for previous analysis result.";
                }
            } else {
                log.warn("Commit hash matches, but no schema path stored in DB");
                reason = "Metadata indicates previous analysis, but result path is missing.";
            }
        } else {
            log.info("Repository URL or commit hash differs.");
            reason = "Repository has been updated or this is a new URL.";
        }

        if (needsAnalysis) {
            log.info("Proceeding with analysis. Reason: {}", reason.isEmpty() ? "Initial analysis or mismatch" : reason);
            performAnalysis(chatId, userId, appUser, validatedUrl, currentCommitHash);
        }
    }

    private void performAnalysis(long chatId, long userId, AppUser appUser, String validatedUrl, String commitHashToSave) {
        String escapedUrl = escapeMarkdownV2(validatedUrl);
        String escapedHash = escapeMarkdownV2(commitHashToSave); // Escape hash too
        String versionPart = commitHashToSave.equals("UNKNOWN") ? "" : " \\(version: `" + escapedHash.substring(0, Math.min(7, escapedHash.length())) + "`\\)";
        sendMessage(chatId, "⏳ Starting analysis for " + escapedUrl + versionPart + "\\.\\.\\. This may take a while\\.");

        Thread analysisThread = new Thread(() -> {
            MDC.put("telegramId", appUser.getTelegramId());
            Path resultSchemaPath = null;
            try {
                boolean cleanupClone = true;
                resultSchemaPath = projectAnalyzerService.analyzeAndProcessProject(validatedUrl, appUser.getTelegramId(), cleanupClone);
                log.info("Analysis thread complete. Schema IR file path: {}", resultSchemaPath);

                try {
                    AppUser userToUpdate = userRepository.findById(appUser.getId())
                            .orElseThrow(() -> new RuntimeException("User not found for saving results"));

                    userToUpdate.setAnalysisResults(validatedUrl, resultSchemaPath.toString(), commitHashToSave);
                    userRepository.save(userToUpdate);
                    log.info("Saved analysis results (schema path and commit hash)");

                    String escapedPath = escapeMarkdownV2(resultSchemaPath.toString());
                    sendMessage(chatId, "✅ Analysis successful\\! Schema saved\\.\nRepository: " + escapedUrl + "\nSchema representation saved at: `" + escapedPath + "`\n\\(Note: This path is on the server\\)\nYou can now ask questions using `/query <your question>`");

                } catch (Exception dbEx) {
                    log.error("Failed to save analysis results path/hash to DB", dbEx);
                    String escapedPath = (resultSchemaPath != null) ? escapeMarkdownV2(resultSchemaPath.toString()) : "UNKNOWN";
                    sendMessage(chatId, "⚠️ Analysis was done \\(schema generated at `" + escapedPath + "`\\), but I failed to save the results metadata\\.");
                }

            } catch (Exception analysisEx) {
                log.error("Analysis failed for URL {}", validatedUrl, analysisEx);
                String reason = analysisEx.getMessage();
                if (analysisEx.getCause() != null) { reason += " \\(Cause: " + analysisEx.getCause().getMessage() + "\\)"; }
                sendMessage(chatId, "❌ Analysis failed for repository: " + escapedUrl + "\nReason: " + escapeMarkdownV2(reason));
            } finally {
                MDC.remove("telegramId");
            }
        });
        analysisThread.setName("AnalysisThread-" + userId);
        analysisThread.start();
    }

    private void handleQueryCommand(long chatId, AppUser appUser, String queryText) {
        log.info("Received /query command with text: '{}'", queryText);
        processUserQuery(chatId, appUser, queryText);
    }

    private void handleQueryInput(long chatId, long userId, AppUser appUser, String queryText) {
        log.info("Received query text while waiting: '{}'", queryText);
        userStates.remove(userId);
        processUserQuery(chatId, appUser, queryText);
    }

    private void processUserQuery(long chatId, AppUser appUser, String userQuery) {
        String schemaPathStr = appUser.getProcessedSchemaPath();
        String lastAnalyzedUrl = appUser.getLastAnalyzedRepoUrl();
        String escapedLastAnalyzedUrl = escapeMarkdownV2(lastAnalyzedUrl); // Escape for message

        if (schemaPathStr == null || schemaPathStr.isBlank()) {
            sendMessage(chatId, "You haven't analyzed any repository yet, or the last analysis failed to save\\. Please use `/analyze_repo` first\\.");
            return;
        }

        Path schemaPath;
        String schemaJson;
        try {
            schemaPath = Paths.get(schemaPathStr);
            if (!Files.exists(schemaPath) || !Files.isRegularFile(schemaPath)) {
                log.error("Schema file not found or invalid: {}", schemaPathStr);
                sendMessage(chatId, "Error: The previously analyzed schema file is missing\\. Please run `/analyze_repo` again for: " + escapedLastAnalyzedUrl);
                return;
            }
            schemaJson = Files.readString(schemaPath, StandardCharsets.UTF_8);
            log.debug("Read schema file content ({} bytes) from {}", schemaJson.length(), schemaPath);

        } catch (Exception e) {
            log.error("Error accessing schema file: {}", schemaPathStr, e);
            sendMessage(chatId, "An error occurred while accessing the schema file \\("+ escapeMarkdownV2(e.getClass().getSimpleName()) +"\\)\\. Please try re\\-analyzing or contact support\\.");
            return;
        }

        sendMessage(chatId, "🧠 Got it\\! Asking the AI based on the schema for " + escapedLastAnalyzedUrl + "\\.\\.\\.");

        Thread llmThread = new Thread(() -> {
            MDC.put("telegramId", appUser.getTelegramId());
            try {
                Optional<String> generatedSqlOpt = geminiService.generateSqlFromSchema(schemaJson, userQuery);

                if (generatedSqlOpt.isPresent()) {
                    String sql = generatedSqlOpt.get();
                    log.info("Generated SQL received.");
                    // Escape backticks and backslashes within the SQL for MarkdownV2 code block
                    String escapedSql = sql.replace("\\", "\\\\").replace("`", "\\`");
                    sendMessage(chatId, "🤖 Generated SQL query:\n\n`" + escapedSql + "`\n\n" +
                            "*Disclaimer:* Review this query before execution\\."); // Use * for italics, escape .
                } else {
                    log.warn("Gemini service did not return SQL for query: '{}'", userQuery);
                    sendMessage(chatId, "Sorry, I couldn't generate an SQL query\\. The AI might have had trouble understanding the request or the schema\\. Please try rephrasing\\.");
                }
            } catch (Exception e) {
                log.error("Error during Gemini SQL generation for query: '{}'", userQuery, e);
                sendMessage(chatId, "An error occurred while generating the SQL query\\.");
            } finally {
                MDC.remove("telegramId");
            }
        });
        llmThread.setName("LLMQueryThread-" + appUser.getTelegramId());
        llmThread.start();
    }

    // Utility method to send a message to a chat using MarkdownV2
    private void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setParseMode("MarkdownV2"); // Use MarkdownV2
        try {
            execute(message);
            log.debug("Sent message to chat ID {}", chatId);
        } catch (TelegramApiException e) {
            // Log specific Markdown error and attempt fallback to plain text
            log.error("Failed to send message with MarkdownV2 to chat ID {}: {}. Retrying as plain text.", chatId, e.getMessage());
            message.setParseMode(null); // Disable Markdown
            try {
                execute(message);
                log.info("Successfully sent message as plain text fallback.");
            } catch (TelegramApiException ex) {
                log.error("Failed to send message as plain text either to chat ID {}: {}", chatId, ex.getMessage());
            }
        }
    }

    // Escapes characters for Telegram MarkdownV2 parse mode
    private String escapeMarkdownV2(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // Escape all required characters: _ * [ ] ( ) ~ ` > # + - = | { } . !
        Matcher matcher = ESCAPE_PATTERN.matcher(text);
        return matcher.replaceAll("\\\\$1");
    }

    // Enum for user states
    private enum UserState {
        IDLE,
        WAITING_FOR_REPO_URL,
        WAITING_FOR_LLM_QUERY
    }
}