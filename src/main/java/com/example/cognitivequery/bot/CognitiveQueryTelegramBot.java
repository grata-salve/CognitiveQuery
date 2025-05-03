package com.example.cognitivequery.bot;

import com.example.cognitivequery.model.AnalysisHistory;
import com.example.cognitivequery.model.AppUser;
import com.example.cognitivequery.repository.AnalysisHistoryRepository;
import com.example.cognitivequery.repository.UserRepository;
import com.example.cognitivequery.service.db.DynamicQueryExecutorService;
import com.example.cognitivequery.service.llm.GeminiService;
import com.example.cognitivequery.service.projectextractor.GitInfoService;
import com.example.cognitivequery.service.projectextractor.ProjectAnalyzerService;
import com.example.cognitivequery.service.security.EncryptionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.example.cognitivequery.service.db.DynamicQueryExecutorService.MAX_SELECT_ROWS;

@Component
@Slf4j
public class CognitiveQueryTelegramBot extends TelegramLongPollingBot {

    private final String botUsername;
    private final UserRepository userRepository;
    private final ProjectAnalyzerService projectAnalyzerService;
    private final WebClient webClient;
    private final GitInfoService gitInfoService;
    private final GeminiService geminiService;
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final EncryptionService encryptionService;
    private final DynamicQueryExecutorService queryExecutorService; // Added field

    private final Map<Long, UserState> userStates = new ConcurrentHashMap<>();
    private final Map<Long, AnalysisInputState> analysisInputState = new ConcurrentHashMap<>();
    private final Map<Long, DbCredentialsInput> credentialsInputState = new ConcurrentHashMap<>();
    private final Map<Long, Long> userQueryContextHistoryId = new ConcurrentHashMap<>();
    private final Map<Long, String> lastGeneratedSql = new ConcurrentHashMap<>();

    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile(
            "^(https?://)?(www\\.)?github\\.com/[\\w.-]+/[\\w.-]+(/)?(?:\\.git)?/?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ESCAPE_PATTERN = Pattern.compile("([_*~`>\\[\\]()#\\+\\-=|{}.!-])");

    private final ExecutorService taskExecutor = Executors.newCachedThreadPool();

    public CognitiveQueryTelegramBot(
            @Value("${telegram.bot.token}") String botToken,
            @Value("${telegram.bot.username}") String botUsername,
            @Value("${backend.api.base-url}") String backendApiBaseUrl,
            UserRepository userRepository,
            ProjectAnalyzerService projectAnalyzerService,
            GitInfoService gitInfoService,
            GeminiService geminiService,
            AnalysisHistoryRepository analysisHistoryRepository,
            EncryptionService encryptionService,
            DynamicQueryExecutorService queryExecutorService, // Added to constructor
            WebClient.Builder webClientBuilder
    ) {
        super(botToken);
        this.botUsername = botUsername;
        this.userRepository = userRepository;
        this.projectAnalyzerService = projectAnalyzerService;
        this.gitInfoService = gitInfoService;
        this.geminiService = geminiService;
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.encryptionService = encryptionService;
        this.queryExecutorService = queryExecutorService; // Assign injected service
        this.webClient = webClientBuilder.baseUrl(backendApiBaseUrl).build();
        log.info("Telegram Bot initialized. Username: {}, Backend API: {}", botUsername, backendApiBaseUrl);
    }

    @PreDestroy
    public void shutdownExecutor() {
        log.info("Shutting down task executor service...");
        taskExecutor.shutdown();
        try {
            if (!taskExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                taskExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            taskExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Task executor service shut down.");
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
        commands.add(new BotCommand("start", "Start interaction"));
        commands.add(new BotCommand("connect_github", "Link GitHub account"));
        commands.add(new BotCommand("analyze_repo", "Analyze repository schema"));
        commands.add(new BotCommand("query", "Ask question about data (uses context)"));
        commands.add(new BotCommand("list_schemas", "List analyzed repositories"));
        commands.add(new BotCommand("use_schema", "Set context for /query by URL"));
        commands.add(new BotCommand("set_db_credentials", "Set DB credentials for a repo"));
        commands.add(new BotCommand("help", "Show available commands"));
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
        if (update.hasCallbackQuery()) {
            handleCallbackQuery(update.getCallbackQuery());
            return;
        }

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
                    sendMessage(chatId, "Sorry, there was a problem accessing user data\\. Please try again later\\.");
                    return;
                }

                UserState currentState = userStates.getOrDefault(userId, UserState.IDLE);
                log.debug("Current state for user {}: {}", userId, currentState);
                boolean processed = false;

                // --- 1. State-based input handling ---
                if (currentState.isCredentialInputState()) {
                    if (!messageText.startsWith("/")) {
                        handleCredentialsInput(chatId, userId, appUser, messageText, currentState);
                        processed = true;
                    } else {
                        userStates.remove(userId);
                        credentialsInputState.remove(userId);
                        analysisInputState.remove(userId);
                        log.warn("Command received, canceling input.");
                    }
                } else if (currentState == UserState.WAITING_FOR_REPO_URL) {
                    if (!messageText.startsWith("/")) {
                        handleRepoUrlInput(chatId, userId, appUser, messageText);
                        processed = true;
                    } else {
                        userStates.remove(userId);
                        log.warn("Command received while waiting for repo URL.");
                    }
                } else if (currentState == UserState.WAITING_FOR_REPO_URL_FOR_CREDS) { // Added this state check
                    if (!messageText.startsWith("/")) {
                        handleRepoUrlForCredsInput(chatId, userId, appUser, messageText);
                        processed = true;
                    } else {
                        userStates.remove(userId);
                        analysisInputState.remove(userId);
                        log.warn("Command received while waiting for repo URL for credentials.");
                    }
                } else if (currentState == UserState.WAITING_FOR_LLM_QUERY) {
                    if (!messageText.startsWith("/")) {
                        handleQueryInput(chatId, userId, appUser, messageText);
                        processed = true;
                    } else {
                        userStates.remove(userId);
                        log.warn("Command received while waiting for query text.");
                    }
                }

                // --- 2. Command handling ---
                if (!processed && messageText.startsWith("/")) {
                    log.debug("Processing message as a command: {}", messageText);
                    String commandPart = messageText.split("\\s+")[0].toLowerCase();

                    if (commandPart.equals("/query")) {
                        String queryText = messageText.length() > 7 ? messageText.substring(7).trim() : "";
                        if (!queryText.isEmpty()) {
                            handleQueryCommand(chatId, appUser, queryText);
                        } else {
                            sendMessage(chatId, "Please provide your query after `/query`\\.\nExample: `/query show all tasks`");
                            userStates.put(userId, UserState.WAITING_FOR_LLM_QUERY);
                            sendMessage(chatId, "Alternatively, just type your query now\\.");
                        }
                        processed = true;
                    } else if (commandPart.equals("/use_schema")) {
                        String repoUrl = messageText.length() > 12 ? messageText.substring(12).trim() : "";
                        handleUseSchemaCommand(chatId, appUser, repoUrl); // Definition added below
                        processed = true;
                    } else if (commandPart.equals("/set_db_credentials")) {
                        analysisInputState.put(userId, new AnalysisInputState());
                        userStates.put(userId, UserState.WAITING_FOR_REPO_URL_FOR_CREDS);
                        sendMessage(chatId, "Which repository's DB credentials do you want to set\\?\nPlease enter the full HTTPS URL:");
                        processed = true;
                    } else {
                        handleCommand(chatId, userId, telegramIdStr, appUser, messageText, userFirstName); // Handle simple commands
                        processed = true;
                    }
                }

                // --- 3. Fallback ---
                if (!processed && currentState == UserState.IDLE) {
                    log.warn("Message '{}' was not processed by any handler in state {}.", messageText, currentState);
                    sendMessage(chatId, "I'm not sure what to do with that\\. Try `/help`\\.");
                }

            } catch (Exception e) {
                log.error("Unhandled exception during update processing", e);
                sendMessage(chatId, "An unexpected error occurred while processing your message\\.");
            } finally {
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

    // Handles simple commands without arguments after specific commands are routed
    private void handleCommand(long chatId, long userId, String telegramIdStr, AppUser appUser, String command, String userFirstName) {
        // Reset state and context only if it's not a command that initiates a stateful flow handled elsewhere
        if (!List.of("/analyze_repo", "/query", "/use_schema", "/set_db_credentials").contains(command.split("\\s+")[0])) {
            userStates.remove(userId);
            userQueryContextHistoryId.remove(userId);
            analysisInputState.remove(userId);
            credentialsInputState.remove(userId);
        }

        if (appUser == null && List.of("/analyze_repo", "/query", "/list_schemas", "/use_schema", "/set_db_credentials").contains(command.split("\\s+")[0])) {
            sendMessage(chatId, "Cannot process command due to a temporary issue accessing user data\\.");
            return;
        }

        switch (command) {
            case "/start":
                sendMessage(chatId, "Hello, " + escapeMarkdownV2(userFirstName) + "\\! I'm CognitiveQuery bot\\.\n➡️ Use `/connect_github`\n➡️ Use `/analyze_repo`\n➡️ Use `/query <your question>`\n➡️ Use `/list_schemas` & `/use_schema <url>`");
                break;
            case "/connect_github":
                initiateGithubAuthFlow(chatId, telegramIdStr);
                break; // Definition added below
            case "/analyze_repo":
                startRepoAnalysisFlow(chatId, userId, appUser);
                break; // Definition added below
            case "/list_schemas":
                handleListSchemasCommand(chatId, appUser);
                break; // Definition added below
            case "/help":
                sendMessage(chatId, "Available commands:\n`/connect_github` \\- Link GitHub\n`/analyze_repo` \\- Analyze repository schema\n`/query <question>` \\- Ask about data \\(uses context\\)\n`/list_schemas` \\- List analyzed repositories\n`/use_schema <repo_url>` \\- Set context for `/query`\n`/set_db_credentials` \\- Set DB credentials for a repo\n`/help` \\- Show this message");
                break;
            default:
                sendMessage(chatId, "Sorry, I don't understand that command\\. Try `/help`\\.");
                break;
        }
    }

    // --- ADDED MISSING METHOD DEFINITIONS ---

    private void handleListSchemasCommand(long chatId, AppUser appUser) {
        List<AnalysisHistory> history = analysisHistoryRepository.findByAppUserOrderByAnalyzedAtDesc(appUser);
        if (history.isEmpty()) {
            sendMessage(chatId, "You haven't analyzed any repositories yet\\. Use `/analyze_repo` first\\.");
            return;
        }
        StringBuilder sb = new StringBuilder("Analyzed repositories \\(latest first\\):\n");
        history.stream()
                .collect(Collectors.groupingBy(AnalysisHistory::getRepositoryUrl, Collectors.maxBy(Comparator.comparing(AnalysisHistory::getAnalyzedAt))))
                .values().stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .sorted(Comparator.comparing(AnalysisHistory::getAnalyzedAt).reversed())
                .limit(15)
                .forEach(h -> {
                    String repoUrl = h.getRepositoryUrl();
                    String commit = h.getCommitHash().substring(0, 7);
                    String date = escapeMarkdownV2(h.getAnalyzedAt().format(DateTimeFormatter.ISO_DATE));
                    String escapedRepoUrl = escapeMarkdownV2(repoUrl);
                    String escapedCommit = escapeMarkdownV2(commit);
                    sb.append(String.format("\\- `%s` \\(Analyzed: %s, Version: `%s`\\)\n", escapedRepoUrl, date, escapedCommit));
                });
        sb.append("\nUse `/use_schema <repo_url>` to set the context for `/query`\\.");
        sendMessage(chatId, sb.toString());
    }

    private void handleUseSchemaCommand(long chatId, AppUser appUser, String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            sendMessage(chatId, "Please provide the repository URL after the command, e\\.g\\., `/use_schema https://github.com/owner/repo`");
            return;
        }
        Optional<AnalysisHistory> historyOpt = analysisHistoryRepository.findFirstByAppUserAndRepositoryUrlOrderByAnalyzedAtDesc(appUser, repoUrl);
        if (historyOpt.isPresent()) {
            userQueryContextHistoryId.put(appUser.getId(), historyOpt.get().getId());
            sendMessage(chatId, "✅ Query context set to: `" + escapeMarkdownV2(repoUrl) + "` \\(version: `" + escapeMarkdownV2(historyOpt.get().getCommitHash().substring(0, 7)) + "`\\)\\.\nNow you can use `/query <your question>`\\.");
        } else {
            sendMessage(chatId, "❌ You haven't analyzed this repository yet: `" + escapeMarkdownV2(repoUrl) + "`\\.\nPlease use `/analyze_repo` first\\.");
        }
    }

    private void initiateGithubAuthFlow(long chatId, String telegramId) {
        log.info("Initiating GitHub auth");
        sendMessage(chatId, "Requesting authorization URL from backend\\.\\.\\.");
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("telegramId", telegramId);
        try {
            ParameterizedTypeReference<Map<String, String>> typeRef = new ParameterizedTypeReference<>() {
            };
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
                String reply = "Please click the link below to authorize with GitHub:\n\n" + authUrl + "\n\nAfter authorization, you can use `/analyze_repo`\\.";
                sendMessage(chatId, reply);
            } else {
                log.error("Failed to get authorization URL from backend. Response: {}", response);
                sendMessage(chatId, "Sorry, I couldn't get the authorization link\\. Unexpected response\\.");
            }
        } catch (Exception e) {
            log.error("Error calling backend API to initiate auth", e);
            sendMessage(chatId, "An error occurred while contacting the backend \\(" + escapeMarkdownV2(e.getMessage()) + "\\)\\.");
        }
    }

    // --- End of added missing methods ---

    private void startRepoAnalysisFlow(long chatId, long userId, AppUser appUser) {
        if (appUser.getGithubId() == null || appUser.getGithubId().isEmpty()) {
            sendMessage(chatId, "You need to connect your GitHub account first\\. Use `/connect_github`\\.");
            return;
        }
        analysisInputState.put(userId, new AnalysisInputState());
        userStates.put(userId, UserState.WAITING_FOR_REPO_URL);
        sendMessage(chatId, "Please send me the full HTTPS URL of the public GitHub repository:");
    }

    private void handleRepoUrlForCredsInput(long chatId, long userId, AppUser appUser, String repoUrl) {
        AnalysisInputState input = analysisInputState.get(userId);
        if (input == null) {
            log.error("AnalysisInputState missing for user {} in REPO_URL_FOR_CREDS", userId);
            userStates.remove(userId);
            return;
        }
        Matcher matcher = GITHUB_URL_PATTERN.matcher(repoUrl.trim());
        if (!matcher.matches()) {
            sendMessage(chatId, "Invalid GitHub URL format\\. Please enter the URL again\\.");
            return;
        }
        input.setRepoUrl(repoUrl.trim());
        log.info("Received repo URL for setting credentials: {}", input.getRepoUrl());
        userStates.put(userId, UserState.WAITING_FOR_DB_HOST);
        input.setCredentials(new DbCredentialsInput());
        sendMessage(chatId, "OK\\. Now enter the DB **hostname** or **IP address** for `" + escapeMarkdownV2(input.getRepoUrl()) + "`:");
    }


    private void handleRepoUrlInput(long chatId, long userId, AppUser appUser, String repoUrl) {
        AnalysisInputState input = analysisInputState.get(userId);
        if (input == null) {
            log.error("AnalysisInputState missing for user {}", userId);
            userStates.remove(userId);
            return;
        }

        Matcher matcher = GITHUB_URL_PATTERN.matcher(repoUrl.trim());
        if (!matcher.matches()) {
            sendMessage(chatId, "Invalid GitHub URL format\\. Please try again\\.");
            return;
        }

        input.setRepoUrl(repoUrl.trim());
        log.info("Handling repo URL input: '{}'", input.getRepoUrl());

        sendMessage(chatId, "Checking repository status\\.\\.\\.");
        Optional<String> currentCommitHashOpt = gitInfoService.getRemoteHeadCommitHash(input.getRepoUrl());
        String currentCommitHash = currentCommitHashOpt.orElse("UNKNOWN");
        input.setCommitHash(currentCommitHash);

        Optional<AnalysisHistory> latestHistoryOpt = analysisHistoryRepository
                .findFirstByAppUserAndRepositoryUrlOrderByAnalyzedAtDesc(appUser, input.getRepoUrl());

        boolean needsAnalysis = true;
        boolean needsCredentials = true;
        String reason = "";
        String existingSchemaPathStr = latestHistoryOpt.map(AnalysisHistory::getSchemaFilePath).orElse(null);
        String escapedValidatedUrl = escapeMarkdownV2(input.getRepoUrl());

        if (latestHistoryOpt.isPresent() && !currentCommitHash.equals("UNKNOWN") && currentCommitHash.equals(latestHistoryOpt.get().getCommitHash())) {
            AnalysisHistory latestHistory = latestHistoryOpt.get();
            log.debug("Commit hash matches the latest analysis for this URL.");
            if (existingSchemaPathStr != null && !existingSchemaPathStr.isBlank()) {
                try {
                    Path existingSchemaPath = Paths.get(existingSchemaPathStr);
                    if (Files.exists(existingSchemaPath) && Files.isRegularFile(existingSchemaPath)) {
                        needsAnalysis = false;
                        userQueryContextHistoryId.put(userId, latestHistory.getId());
                        String escapedPath = escapeMarkdownV2(existingSchemaPath.toString());
                        sendMessage(chatId, "✅ This repository version \\(" + escapedValidatedUrl + "\\) was already analyzed\\. Query context set\\.\nSchema available at: `" + escapedPath + "`");
                        userStates.remove(userId);
                        analysisInputState.remove(userId);
                    } else {
                        reason = "Previous result file missing.";
                    }
                } catch (Exception e) {
                    reason = "Invalid stored path.";
                    log.error("Invalid schema file path stored: {}", existingSchemaPathStr, e);
                }
            } else {
                reason = "Result path missing in history.";
            }

            if (needsAnalysis && latestHistory.hasCredentials()) {
                log.info("Re-analysis needed, reusing credentials from history ID {}", latestHistory.getId());
                input.setCredentials(DbCredentialsInput.fromHistory(latestHistory));
                needsCredentials = false;
            } else if (needsAnalysis) {
                reason += " Needs new credentials.";
            }
        } else if (latestHistoryOpt.isPresent()) {
            reason = "Repository updated.";
        } else {
            reason = "New repository URL.";
        }

        if (needsAnalysis) {
            log.info("Proceeding with analysis. Reason: {}", reason);
            if (needsCredentials) {
                userStates.put(userId, UserState.WAITING_FOR_DB_HOST);
                sendMessage(chatId, "Analysis required\\. Let's get DB credentials for this repository\\.\nEnter DB **hostname** or **IP**:");
            } else {
                log.info("Proceeding directly to analysis using reused credentials.");
                performAnalysis(chatId, userId, appUser, input);
                userStates.remove(userId);
                analysisInputState.remove(userId);
            }
        }
    }

    private void handleCredentialsInput(long chatId, long userId, AppUser appUser, String text, UserState currentState) {
        AnalysisInputState analysisInput = analysisInputState.get(userId);
        if (analysisInput == null || analysisInput.getCredentials() == null) {
            log.error("Input state missing during credential input for user {}, state {}", userId, currentState);
            userStates.remove(userId);
            sendMessage(chatId, "Something went wrong\\. Please start again\\.");
            return;
        }
        DbCredentialsInput currentCreds = analysisInput.getCredentials();
        try {
            switch (currentState) {
                case WAITING_FOR_DB_HOST:
                    currentCreds.setHost(text.trim());
                    userStates.put(userId, UserState.WAITING_FOR_DB_PORT);
                    sendMessage(chatId, "Got it\\. DB **port** \\(e\\.g\\., `5432`\\):");
                    break;
                case WAITING_FOR_DB_PORT:
                    currentCreds.setPort(Integer.parseInt(text.trim()));
                    userStates.put(userId, UserState.WAITING_FOR_DB_NAME);
                    sendMessage(chatId, "OK\\. **Database name**:");
                    break;
                case WAITING_FOR_DB_NAME:
                    currentCreds.setName(text.trim());
                    userStates.put(userId, UserState.WAITING_FOR_DB_USER);
                    sendMessage(chatId, "DB **username**:");
                    break;
                case WAITING_FOR_DB_USER:
                    currentCreds.setUsername(text.trim());
                    userStates.put(userId, UserState.WAITING_FOR_DB_PASSWORD);
                    sendMessage(chatId, "Finally, DB **password**:");
                    break;
                case WAITING_FOR_DB_PASSWORD:
                    String plainPassword = text.trim();
                    Optional<String> encryptedPasswordOpt = encryptionService.encrypt(plainPassword);
                    if (encryptedPasswordOpt.isEmpty()) {
                        throw new RuntimeException("Failed to encrypt password.");
                    }
                    currentCreds.setEncryptedPassword(encryptedPasswordOpt.get());
                    log.info("Credentials input complete for user {}.", userId);
                    if (analysisInput.getRepoUrl() != null) { // If initiated from /analyze_repo flow
                        log.info("Proceeding to analysis after credential input.");
                        performAnalysis(chatId, userId, appUser, analysisInput);
                    } else { // If initiated from /set_db_credentials flow
                        Optional<AnalysisHistory> historyOpt = analysisHistoryRepository.findFirstByAppUserAndRepositoryUrlOrderByAnalyzedAtDesc(appUser, analysisInput.getRepoUrl());
                        if (historyOpt.isPresent()) {
                            AnalysisHistory history = historyOpt.get();
                            history.setDbHost(currentCreds.getHost());
                            history.setDbPort(currentCreds.getPort());
                            history.setDbName(currentCreds.getName());
                            history.setDbUser(currentCreds.getUsername());
                            history.setDbPasswordEncrypted(currentCreds.getEncryptedPassword());
                            analysisHistoryRepository.save(history);
                            log.info("Updated credentials for existing analysis history ID {}", history.getId());
                            sendMessage(chatId, "✅ Database credentials updated successfully for `" + escapeMarkdownV2(analysisInput.getRepoUrl()) + "`\\!");
                        } else {
                            log.warn("Cannot update credentials. No analysis history found for URL: {}", analysisInput.getRepoUrl());
                            sendMessage(chatId, "⚠️ Could not find a previous analysis for `" + escapeMarkdownV2(analysisInput.getRepoUrl()) + "`\\. Credentials not saved\\. Please run `/analyze_repo` first\\.");
                        }
                    }
                    userStates.remove(userId);
                    analysisInputState.remove(userId);
                    break;
                default:
                    log.warn("Unexpected state {} during credential input.", currentState);
                    userStates.remove(userId);
                    analysisInputState.remove(userId);
            }
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Invalid port number\\. Please enter a valid number\\.");
        } catch (Exception e) {
            log.error("Error processing credential input", e);
            userStates.remove(userId);
            analysisInputState.remove(userId);
            sendMessage(chatId, "An error occurred\\. Please try again\\.");
        }
    }


    private void performAnalysis(long chatId, long userId, AppUser appUser, AnalysisInputState input) {
        String validatedUrl = input.getRepoUrl();
        String commitHashToSave = input.getCommitHash();
        DbCredentialsInput credentials = input.getCredentials();
        if (credentials == null || credentials.getEncryptedPassword() == null) {
            log.error("Credentials missing in performAnalysis for user {}.", userId);
            sendMessage(chatId, "Error: DB credentials missing\\.");
            return;
        }

        String escapedUrl = escapeMarkdownV2(validatedUrl);
        String escapedHash = escapeMarkdownV2(commitHashToSave);
        String versionPart = commitHashToSave.equals("UNKNOWN") ? "" : " \\(version: `" + escapedHash.substring(0, Math.min(7, escapedHash.length())) + "`\\)";
        sendMessage(chatId, "⏳ Starting analysis for " + escapedUrl + versionPart + "\\.\\.\\. This may take a while\\.");

        taskExecutor.submit(() -> {
            MDC.put("telegramId", appUser.getTelegramId());
            Path resultSchemaPath = null;
            String oldSchemaPathToDelete = null;
            try {
                List<AnalysisHistory> oldHistories = analysisHistoryRepository.findByAppUserAndRepositoryUrl(appUser, validatedUrl);
                oldSchemaPathToDelete = oldHistories.stream().max(Comparator.comparing(AnalysisHistory::getAnalyzedAt)).map(AnalysisHistory::getSchemaFilePath).orElse(null);

                boolean cleanupClone = true;
                resultSchemaPath = projectAnalyzerService.analyzeAndProcessProject(validatedUrl, appUser.getTelegramId(), cleanupClone);
                log.info("Analysis successful. Schema path: {}", resultSchemaPath);

                try {
                    AnalysisHistory newHistory = new AnalysisHistory(appUser, validatedUrl, commitHashToSave, resultSchemaPath.toString());
                    newHistory.setDbHost(credentials.getHost());
                    newHistory.setDbPort(credentials.getPort());
                    newHistory.setDbName(credentials.getName());
                    newHistory.setDbUser(credentials.getUsername());
                    newHistory.setDbPasswordEncrypted(credentials.getEncryptedPassword());
                    AnalysisHistory savedHistory = analysisHistoryRepository.save(newHistory);
                    log.info("Saved new analysis history record ID: {}", savedHistory.getId());

                    List<AnalysisHistory> recordsToDelete = oldHistories.stream().toList();
                    if (!recordsToDelete.isEmpty()) {
                        analysisHistoryRepository.deleteAll(recordsToDelete);
                        log.info("Deleted {} old history records.", recordsToDelete.size());
                    }
                    if (oldSchemaPathToDelete != null && !oldSchemaPathToDelete.isBlank() && !oldSchemaPathToDelete.equals(resultSchemaPath.toString())) {
                        try {
                            if (Files.deleteIfExists(Paths.get(oldSchemaPathToDelete))) {
                                log.info("Deleted previous schema file: {}", oldSchemaPathToDelete);
                            }
                        } catch (Exception e) {
                            log.error("Failed to delete previous schema file: {}", oldSchemaPathToDelete, e);
                        }
                    }
                    userQueryContextHistoryId.put(userId, savedHistory.getId());
                    String currentEscapedPath = escapeMarkdownV2(resultSchemaPath.toString());
                    sendMessage(chatId, "✅ Analysis successful\\! Schema saved\\.\nRepository: " + escapedUrl + "\nQuery context set\\.\nUse `/query <your question>`");

                } catch (Exception dbEx) {
                    log.error("Failed to save/cleanup analysis results", dbEx);
                    sendMessage(chatId, "⚠️ Analysis done, but failed to save results/cleanup\\.");
                }
            } catch (Exception analysisEx) { /* ... Handle analysis error ... */ } finally {
                MDC.remove("telegramId");
            }
        });
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
        Long targetHistoryId = userQueryContextHistoryId.get(appUser.getId());
        Optional<AnalysisHistory> targetHistoryOpt;
        String contextMessage;

        if (targetHistoryId != null) {
            log.info("Using query context for History ID: {}", targetHistoryId);
            targetHistoryOpt = analysisHistoryRepository.findById(targetHistoryId);
            contextMessage = targetHistoryOpt.map(h -> "based on the schema for `" + escapeMarkdownV2(h.getRepositoryUrl()) + "`").orElse("based on a previously set context \\(not found\\?\\)");
            if (targetHistoryOpt.isEmpty()) {
                sendMessage(chatId, "Context error\\. Please use `/list_schemas` and `/use_schema` again\\.");
                userQueryContextHistoryId.remove(appUser.getId());
                return;
            }
        } else {
            log.info("No query context set, using the latest analyzed repository.");
            targetHistoryOpt = analysisHistoryRepository.findFirstByAppUserOrderByAnalyzedAtDesc(appUser);
            if (targetHistoryOpt.isEmpty()) {
                sendMessage(chatId, "You haven't analyzed any repository yet\\. Please use `/analyze_repo` first\\.");
                return;
            }
            contextMessage = "based on the *latest* schema analyzed \\(`" + escapeMarkdownV2(targetHistoryOpt.get().getRepositoryUrl()) + "`\\)";
            targetHistoryId = targetHistoryOpt.get().getId();
            log.info("Defaulting query context to History ID: {}", targetHistoryId);
        }

        AnalysisHistory targetHistory = targetHistoryOpt.get();
        String schemaPathStr = targetHistory.getSchemaFilePath();
        String commitHash = targetHistory.getCommitHash();
        String escapedRepoUrlForQuery = escapeMarkdownV2(targetHistory.getRepositoryUrl());

        Path schemaPath;
        String schemaJson;
        try {
            schemaPath = Paths.get(schemaPathStr);
            if (!Files.exists(schemaPath) || !Files.isRegularFile(schemaPath)) {
                log.error("Schema file not found or invalid: {}", schemaPathStr);
                sendMessage(chatId, "Error: The schema file for " + escapedRepoUrlForQuery + " is missing\\. Please run `/analyze_repo` again\\.");
                return;
            }
            schemaJson = Files.readString(schemaPath, StandardCharsets.UTF_8);
            log.debug("Read schema file content ({} bytes) from {}", schemaJson.length(), schemaPath);
        } catch (Exception e) {
            log.error("Error accessing schema file: {}", schemaPathStr, e);
            sendMessage(chatId, "An error occurred while accessing the schema file \\(" + escapeMarkdownV2(e.getClass().getSimpleName()) + "\\)\\. Please try re\\-analyzing\\.");
            return;
        }

        sendMessage(chatId, "🧠 Got it\\! Asking the AI " + contextMessage + " \\(`" + escapeMarkdownV2(commitHash).substring(0, 7) + "`\\)\\.\\.\\.");

        final Long finalTargetHistoryId = targetHistoryId;

        taskExecutor.submit(() -> {
            MDC.put("telegramId", appUser.getTelegramId());
            try {
                Optional<String> generatedSqlOpt = geminiService.generateSqlFromSchema(schemaJson, userQuery);
                if (generatedSqlOpt.isPresent()) {
                    String sql = generatedSqlOpt.get();
                    log.info("Generated SQL received for History ID: {}", finalTargetHistoryId);
                    String escapedSql = sql.replace("\\", "\\\\").replace("`", "\\`");
                    lastGeneratedSql.put(appUser.getId(), finalTargetHistoryId + ":" + sql);

                    InlineKeyboardButton executeButton = InlineKeyboardButton.builder().text("🚀 Execute Query").callbackData("execute_sql:" + finalTargetHistoryId).build();
                    InlineKeyboardMarkup keyboardMarkup = InlineKeyboardMarkup.builder().keyboardRow(List.of(executeButton)).build();
                    SendMessage resultMessage = SendMessage.builder().chatId(chatId).text("🤖 Generated SQL query:\n\n`" + escapedSql + "`\n\n*Disclaimer:* Review before execution\\.\nPress button to run\\.").parseMode("MarkdownV2").replyMarkup(keyboardMarkup).build();
                    try {
                        execute(resultMessage);
                    } catch (TelegramApiException e) {
                        sendMessage(chatId, "🤖 Generated SQL query:\n\n`" + escapedSql + "`\n\n*Disclaimer:* Review before execution \\(button error\\)\\.");
                    }
                } else {
                    sendMessage(chatId, "Sorry, I couldn't generate an SQL query\\. Try rephrasing\\.");
                }
            } catch (Exception e) {
                log.error("Error during Gemini SQL generation", e);
                sendMessage(chatId, "An error occurred during SQL generation\\.");
            } finally {
                MDC.remove("telegramId");
            }
        });
    }

    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        String callbackData = callbackQuery.getData();
        long chatId = callbackQuery.getMessage().getChatId();
        long userId = callbackQuery.getFrom().getId();
        String telegramIdStr = String.valueOf(userId);
        String callbackQueryId = callbackQuery.getId(); // ID to answer the callback

        MDC.put("telegramId", telegramIdStr);
        try {
            log.info("Received callback query data: {}", callbackData);
            String answerText = null; // Text for the popup notification

            if (callbackData != null && callbackData.startsWith("execute_sql:")) {
                String[] parts = callbackData.split(":", 2);
                if (parts.length != 2) {
                    log.error("Invalid callback data format: {}", callbackData);
                    answerText = "Error: Invalid action";
                    return;
                }

                long historyIdToExecute;
                try {
                    historyIdToExecute = Long.parseLong(parts[1]);
                } catch (NumberFormatException e) {
                    log.error("Invalid history ID: {}", parts[1]);
                    answerText = "Error: Invalid ID";
                    return;
                }

                AppUser appUser = findOrCreateUser(telegramIdStr);
                String storedData = lastGeneratedSql.get(userId);
                String sqlToExecute = null;
                if (storedData != null && storedData.startsWith(historyIdToExecute + ":")) {
                    sqlToExecute = storedData.substring(String.valueOf(historyIdToExecute).length() + 1);
                }

                if (appUser != null && sqlToExecute != null) {
                    Optional<AnalysisHistory> historyOpt = analysisHistoryRepository.findById(historyIdToExecute);
                    if (historyOpt.isPresent() && Objects.equals(historyOpt.get().getAppUser().getId(), appUser.getId())) {
                        AnalysisHistory history = historyOpt.get();
                        if (history.hasCredentials()) {
                            sendMessage(chatId, "🚀 Executing SQL query\\.\\.\\."); // Send separate message
                            answerText = "Execution started..."; // Text for popup
                            lastGeneratedSql.remove(userId);

                            final String finalSql = sqlToExecute;
                            taskExecutor.submit(() -> { // Execute in background
                                MDC.put("telegramId", telegramIdStr);
                                try {
                                    DynamicQueryExecutorService.QueryResult result = queryExecutorService.executeQuery(
                                            history.getDbHost(), history.getDbPort(), history.getDbName(),
                                            history.getDbUser(), history.getDbPasswordEncrypted(), finalSql);
                                    if (result.isSuccess()) {
                                        if (result.type() == DynamicQueryExecutorService.QueryType.SELECT) {
                                            sendSelectResult(chatId, (List<Map<String, Object>>) result.data());
                                        } else if (result.type() == DynamicQueryExecutorService.QueryType.UPDATE) {
                                            sendMessage(chatId, "✅ Query executed successfully\\. Rows affected: " + result.data());
                                        } else {
                                            sendMessage(chatId, "✅ Query executed, unknown result type\\.");
                                        }
                                    } else {
                                        sendMessage(chatId, "❌ Query execution failed: " + escapeMarkdownV2(result.errorMessage()));
                                    }
                                } catch (Exception e) {
                                    log.error("Error executing SQL", e);
                                    sendMessage(chatId, "An unexpected error occurred during SQL execution\\.");
                                } finally {
                                    MDC.remove("telegramId");
                                }
                            });
                        } else {
                            answerText = "❌ Error: DB credentials missing!";
                            sendMessage(chatId, "❌ Cannot execute: DB credentials missing for this analysis history\\. Use `/set_db_credentials`\\.");
                        }
                    } else {
                        answerText = "❌ Error: Invalid history!";
                        sendMessage(chatId, "❌ Cannot execute: Analysis history not found or invalid\\.");
                    }
                } else {
                    answerText = "❌ Error: Query not found!";
                    sendMessage(chatId, "Error: Could not find user or SQL query to execute\\.");
                }
            } else {
                log.warn("Received unknown callback data: {}", callbackData);
                answerText = "Unknown action";
            }

            // Answer the callback query to remove the "loading" state on the button
            AnswerCallbackQuery answer = AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQueryId)
                    .text(answerText) // Optional: show a brief notification
                    // .showAlert(false) // Set to true to show a popup alert
                    .build();
            try {
                execute(answer);
            } catch (TelegramApiException e) {
                log.error("Failed to answer callback query", e);
            }

        } catch (Exception e) {
            log.error("Error processing callback query", e);
        } finally {
            MDC.remove("telegramId");
        }
    }

    private void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setParseMode("MarkdownV2");
        try {
            execute(message);
            log.debug("Sent message to chat ID {}", chatId);
        } catch (TelegramApiException e) {
            log.error("Failed to send message with MarkdownV2 to chat ID {}: {}. Retrying as plain text.", chatId, e.getMessage());
            message.setParseMode(null);
            try {
                execute(message);
                log.info("Successfully sent message as plain text fallback.");
            } catch (TelegramApiException ex) {
                log.error("Failed to send message as plain text either to chat ID {}: {}", chatId, ex.getMessage());
            }
        }
    }

    private String escapeMarkdownV2(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher matcher = ESCAPE_PATTERN.matcher(text);
        return matcher.replaceAll("\\\\$1");
    }

    private enum UserState {
        IDLE,
        WAITING_FOR_REPO_URL,
        WAITING_FOR_LLM_QUERY,
        WAITING_FOR_REPO_URL_FOR_CREDS, // New state: waiting for repo URL before asking for credentials
        WAITING_FOR_DB_HOST,
        WAITING_FOR_DB_PORT,
        WAITING_FOR_DB_NAME,
        WAITING_FOR_DB_USER,
        WAITING_FOR_DB_PASSWORD;

        // Helper method to check if current state is for credential input
        public boolean isCredentialInputState() {
            return this == WAITING_FOR_DB_HOST || this == WAITING_FOR_DB_PORT || this == WAITING_FOR_DB_NAME ||
                    this == WAITING_FOR_DB_USER || this == WAITING_FOR_DB_PASSWORD;
        }
    }

    // Helper class to store intermediate state during analysis/credential input
    @Data
    private static class AnalysisInputState {
        private String repoUrl;
        private String commitHash;
        private DbCredentialsInput credentials;
    }

    // Helper class to store credential parts during input
    @Data
    private static class DbCredentialsInput {
        private String host;
        private Integer port;
        private String name;
        private String username;
        private String encryptedPassword;

        // Factory method to copy credentials from history (used for reusing credentials)
        public static DbCredentialsInput fromHistory(AnalysisHistory h) {
            if (!h.hasCredentials()) return null; // Return null if history lacks credentials
            DbCredentialsInput i = new DbCredentialsInput();
            i.host = h.getDbHost();
            i.port = h.getDbPort();
            i.name = h.getDbName();
            i.username = h.getDbUser();
            i.encryptedPassword = h.getDbPasswordEncrypted();
            return i;
        }
    }

    // Helper method to format SELECT results (basic table)
    private void sendSelectResult(long chatId, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            sendMessage(chatId, "✅ Query executed successfully, but it returned no rows\\.");
            return;
        }

        StringBuilder resultText = new StringBuilder("✅ Query executed successfully\\. Results" +
                (rows.size() >= MAX_SELECT_ROWS ? " \\(showing first " + MAX_SELECT_ROWS + " rows\\):" : ":") +
                "\n\n");

        resultText.append("```\n"); // Start code block

        List<String> headers = new ArrayList<>(rows.getFirst().keySet());
        resultText.append(String.join(" | ", headers)).append("\n");
        resultText.append(headers.stream().map(h -> "-".repeat(h.length())).collect(Collectors.joining("-|-", "-", "-"))).append("\n"); // Separator line

        for (Map<String, Object> row : rows) {
            List<String> values = headers.stream()
                    .map(row::get)
                    .map(value -> value != null ? value.toString() : "NULL")
                    .map(str -> str.length() > 30 ? str.substring(0, 27) + "..." : str) // Truncate long values
                    .map(str -> str.replace("\n", " ").replace("\r", " ")) // Remove newlines from values
                    .collect(Collectors.toList());
            resultText.append(String.join(" | ", values)).append("\n");
        }

        resultText.append("```"); // End code block

        String fullMessage = resultText.toString();
        int maxLen = 4000; // Keep below Telegram limit (4096) safely

        if (fullMessage.length() <= maxLen) {
            // Escape the whole message AFTER formatting for MarkdownV2
            sendMessage(chatId, escapeMarkdownV2(fullMessage));
        } else {
            log.warn("Generated SELECT result is too long ({} chars), sending truncated.", fullMessage.length());
            // Escape only the truncated part
            sendMessage(chatId, escapeMarkdownV2(fullMessage.substring(0, maxLen - 30)) + "\n```\n\\.\\.\\. \\(results truncated\\)");
        }
    }
}