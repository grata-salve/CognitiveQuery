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
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.IOException;
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
    private final DynamicQueryExecutorService queryExecutorService;

    private final Map<Long, UserState> userStates = new ConcurrentHashMap<>();
    private final Map<Long, AnalysisInputState> analysisInputState = new ConcurrentHashMap<>();
    private final Map<Long, DbCredentialsInput> credentialsInputState = new ConcurrentHashMap<>();
    private final Map<Long, Long> userQueryContextHistoryId = new ConcurrentHashMap<>();
    private final Map<Long, String> lastGeneratedSql = new ConcurrentHashMap<>();

    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile(
            "^(https?://)?(www\\.)?github\\.com/[\\w.-]+/[\\w.-]+(/)?(?:\\.git)?/?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BASIC_URL_PATTERN = Pattern.compile("^https?://.+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ESCAPE_PATTERN = Pattern.compile("([_*~`>\\[\\]()#\\+\\-=|{}.!-])");

    private static final int MAX_SELECT_ROWS_FOR_CHAT_TEXT = 20;
    private static final int MAX_COLUMNS_FOR_CHAT_TEXT = 4;

    private static final String CSV_FLAG = "--csv";
    private static final String TXT_FLAG = "--txt";

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
            DynamicQueryExecutorService queryExecutorService,
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
        this.queryExecutorService = queryExecutorService;
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
        commands.add(new BotCommand("query", "Ask about data (add --csv or --txt for file output)"));
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
                log.info("Received message from {}. Chat ID: {}, Text: '{}'", userFirstName, chatId, messageText);

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
                        userStates.remove(userId); credentialsInputState.remove(userId); analysisInputState.remove(userId);
                        log.warn("Command received, canceling credentials input.");
                    }
                } else if (currentState == UserState.WAITING_FOR_REPO_URL) {
                    if (!messageText.startsWith("/")) {
                        handleRepoUrlInput(chatId, userId, appUser, messageText);
                        processed = true;
                    } else {
                        userStates.remove(userId); log.warn("Command received while waiting for repo URL.");
                    }
                } else if (currentState == UserState.WAITING_FOR_REPO_URL_FOR_CREDS) {
                    if (!messageText.startsWith("/")) {
                        handleRepoUrlForCredsInput(chatId, userId, appUser, messageText);
                        processed = true;
                    } else {
                        userStates.remove(userId); credentialsInputState.remove(userId);
                        log.warn("Command received while waiting for repo URL for credentials.");
                    }
                } else if (currentState == UserState.WAITING_FOR_LLM_QUERY) {
                    if (!messageText.startsWith("/")) {
                        handleQueryInput(chatId, userId, appUser, messageText);
                        processed = true;
                    } else {
                        userStates.remove(userId); log.warn("Command received while waiting for query text.");
                    }
                }

                // --- 2. Command handling ---
                if (!processed && messageText.startsWith("/")) {
                    log.debug("Processing message as a command: {}", messageText);
                    String commandPart = messageText.split("\\s+")[0].toLowerCase();

                    if (commandPart.equals("/query")) {
                        String queryTextWithPotentialFlag = messageText.length() > 7 ? messageText.substring(7).trim() : "";

                        boolean isJustAFlag = (queryTextWithPotentialFlag.equalsIgnoreCase(CSV_FLAG) && queryTextWithPotentialFlag.length() == CSV_FLAG.length()) ||
                                (queryTextWithPotentialFlag.equalsIgnoreCase(TXT_FLAG) && queryTextWithPotentialFlag.length() == TXT_FLAG.length());

                        if (!queryTextWithPotentialFlag.isEmpty() && !isJustAFlag) {
                            handleQueryCommand(chatId, appUser, queryTextWithPotentialFlag);
                        } else {
                            sendMessage(chatId, "Please provide your query after `/query`\\.\nExample: `/query show all tasks` or `/query show all tasks --csv`");
                            userStates.put(userId, UserState.WAITING_FOR_LLM_QUERY);
                            sendMessage(chatId, "Alternatively, just type your query now\\.");
                        }
                        processed = true;
                    } else if (commandPart.equals("/use_schema")) {
                        String repoUrl = messageText.length() > 12 ? messageText.substring(12).trim() : "";
                        handleUseSchemaCommand(chatId, appUser, repoUrl);
                        processed = true;
                    } else if (commandPart.equals("/set_db_credentials")) {
                        credentialsInputState.put(userId, new DbCredentialsInput());
                        userStates.put(userId, UserState.WAITING_FOR_REPO_URL_FOR_CREDS);
                        sendMessage(chatId, "Which repository's DB credentials do you want to set or update\\?\nPlease enter the full URL:");
                        processed = true;
                    } else {
                        handleCommand(chatId, userId, telegramIdStr, appUser, messageText, userFirstName);
                        processed = true;
                    }
                }

                // --- 3. Fallback ---
                if (!processed && currentState == UserState.IDLE) {
                    log.warn("Message '{}' was not processed by any handler in state {}.", messageText, currentState);
                    sendMessage(chatId, "I'm not sure what to do with that\\. Try `/help`\\.");
                }

            } catch (Exception e) {
                log.error("Unhandled exception during update processing for message: " + message.getText(), e);
                sendMessage(chatId, "An unexpected error occurred\\.");
            } finally {
                MDC.remove("telegramId");
            }
        }
    }

    private AppUser findOrCreateUser(String telegramIdStr) {
        try {
            return userRepository.findByTelegramId(telegramIdStr).orElseGet(() -> {
                log.info("User not found, creating new for Telegram ID: {}", telegramIdStr);
                AppUser newUser = new AppUser(telegramIdStr);
                return userRepository.save(newUser);
            });
        } catch (Exception e) {
            log.error("Database error fetching/creating user for Telegram ID: {}.", telegramIdStr, e);
            return null;
        }
    }

    private void handleCommand(long chatId, long userId, String telegramIdStr, AppUser appUser, String command, String userFirstName) {
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
                sendMessage(chatId, "Hello, " + escapeMarkdownV2(userFirstName) + "\\! I'm CognitiveQuery bot\\.\n➡️ Use `/connect_github`\n➡️ Use `/analyze_repo`\n➡️ Use `/query <your question>`\n➡️ Use `/list_schemas` & `/use_schema <url>`\n➡️ Use `/set_db_credentials`");
                break;
            case "/connect_github":
                initiateGithubAuthFlow(chatId, telegramIdStr);
                break;
            case "/analyze_repo":
                startRepoAnalysisFlow(chatId, userId, appUser);
                break;
            case "/list_schemas":
                handleListSchemasCommand(chatId, appUser);
                break;
            case "/help":
                sendMessage(chatId, "Available commands:\n`/connect_github` \\- Link GitHub\n`/analyze_repo` \\- Analyze repository schema\n`/query <question>` \\- Ask about data \\(add `--csv` or `--txt` for file output\\)\n`/list_schemas` \\- List analyzed repositories\n`/use_schema <repo_url>` \\- Set context for `/query`\n`/set_db_credentials` \\- Set DB credentials for a repo\n`/help` \\- Show this message");
                break;
            default:
                if (!command.startsWith("/query") && !command.startsWith("/use_schema") && !command.startsWith("/set_db_credentials")) {
                    sendMessage(chatId, "Sorry, I don't understand that command\\. Try `/help`\\.");
                }
                break;
        }
    }

    private void handleListSchemasCommand(long chatId, AppUser appUser) {
        List<AnalysisHistory> history = analysisHistoryRepository.findByAppUserOrderByAnalyzedAtDesc(appUser);
        if (history.isEmpty()) {
            sendMessage(chatId, "You haven't analyzed any repositories yet\\. Use `/analyze_repo` first\\.");
            return;
        }
        StringBuilder sb = new StringBuilder("Analyzed repositories \\(latest first\\):\n");
        history.stream()
                .collect(Collectors.groupingBy(AnalysisHistory::getRepositoryUrl, Collectors.maxBy(Comparator.comparing(AnalysisHistory::getAnalyzedAt))))
                .values().stream().filter(Optional::isPresent).map(Optional::get)
                .sorted(Comparator.comparing(AnalysisHistory::getAnalyzedAt).reversed()).limit(15)
                .forEach(h -> {
                    String escapedRepoUrl = escapeMarkdownV2(h.getRepositoryUrl());
                    String escapedCommit = escapeMarkdownV2(h.getCommitHash().substring(0, 7));
                    String date = escapeMarkdownV2(h.getAnalyzedAt().format(DateTimeFormatter.ISO_DATE));
                    sb.append(String.format("\\- `%s` \\(Analyzed: %s, Version: `%s`\\)%s\n", escapedRepoUrl, date, escapedCommit, h.hasCredentials() ? " \\(DB creds saved\\)" : ""));
                });
        sb.append("\nUse `/use_schema <repo_url>` to set the context for `/query`\\.\nUse `/set_db_credentials` to add/update DB details\\.");
        sendMessage(chatId, sb.toString());
    }

    private void handleUseSchemaCommand(long chatId, AppUser appUser, String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            sendMessage(chatId, "Please provide the repository URL, e\\.g\\., `/use_schema https://github.com/owner/repo`");
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
        log.info("Initiating GitHub auth for Telegram ID: {}", telegramId);
        sendMessage(chatId, "Requesting authorization URL from backend\\.\\.\\.");
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
                            cr -> cr.bodyToMono(String.class).map(eB -> new RuntimeException("BE API err: " + cr.statusCode() + " Body: " + eB)))
                    .bodyToMono(typeRef)
                    .block();

            if (response != null && response.containsKey("authorizationUrl")) {
                String authUrl = response.get("authorizationUrl");
                log.info("Received authorization URL (first 100 chars): {}...", authUrl.substring(0, Math.min(authUrl.length(), 100)));
                String reply = "Please click the link below to authorize with GitHub:\n\n" + authUrl +
                        "\n\nAfter authorization, you can use `/analyze_repo`\\.";
                sendMessage(chatId, reply);
            } else {
                log.error("Failed to get GitHub authorization URL. Response: {}", response);
                sendMessage(chatId, "Sorry, I couldn't get the authorization link\\. Unexpected response from backend\\.");
            }
        } catch (Exception e) {
            log.error("Error calling backend API for GitHub auth", e);
            sendMessage(chatId, "An error occurred while contacting backend \\(" + escapeMarkdownV2(e.getMessage()) + "\\)\\.");
        }
    }

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
        DbCredentialsInput credsInput = credentialsInputState.computeIfAbsent(userId, k -> new DbCredentialsInput());
        Matcher matcher = BASIC_URL_PATTERN.matcher(repoUrl.trim());
        if (!matcher.matches()) {
            sendMessage(chatId, "The input doesn't look like a valid URL starting with `http://` or `https://`\\. Please enter the URL again\\.");
            return;
        }
        credsInput.setAssociatedRepoUrl(repoUrl.trim());
        log.info("Received repo URL for setting credentials: {}", credsInput.getAssociatedRepoUrl());
        userStates.put(userId, UserState.WAITING_FOR_DB_HOST);
        sendMessage(chatId, "OK\\. Now enter the DB **hostname** or **IP address** for `" + escapeMarkdownV2(credsInput.getAssociatedRepoUrl()) + "`:");
    }

    private void handleRepoUrlInput(long chatId, long userId, AppUser appUser, String repoUrl) {
        AnalysisInputState input = analysisInputState.get(userId);
        if (input == null) {
            log.error("AnalysisInputState missing for user {} during repo URL input.", userId);
            userStates.remove(userId);
            sendMessage(chatId, "An internal error occurred\\. Please try starting the analysis again\\.");
            return;
        }

        Matcher matcher = GITHUB_URL_PATTERN.matcher(repoUrl.trim());
        if (!matcher.matches()) {
            sendMessage(chatId, "Invalid GitHub URL format\\. Please enter a valid GitHub repository URL \\(e\\.g\\., `https://github.com/owner/repo`\\)\\.");
            return;
        }

        input.setRepoUrl(repoUrl.trim());
        log.info("Handling repo URL input for analysis: '{}'", input.getRepoUrl());

        sendMessage(chatId, "Checking repository status\\.\\.\\.");
        Optional<String> currentCommitHashOpt = gitInfoService.getRemoteHeadCommitHash(input.getRepoUrl());
        String currentCommitHash = currentCommitHashOpt.orElse("UNKNOWN_COMMIT");
        input.setCommitHash(currentCommitHash);

        Optional<AnalysisHistory> latestHistoryOpt = analysisHistoryRepository.findFirstByAppUserAndRepositoryUrlOrderByAnalyzedAtDesc(appUser, input.getRepoUrl());

        boolean needsAnalysis = true;
        boolean needsCredentials = true;
        String reason = "";
        String escapedValidatedUrl = escapeMarkdownV2(input.getRepoUrl());

        if (latestHistoryOpt.isPresent()) {
            AnalysisHistory latestHistory = latestHistoryOpt.get();
            String existingSchemaPathStr = latestHistory.getSchemaFilePath();

            if (!currentCommitHash.equals("UNKNOWN_COMMIT") && currentCommitHash.equals(latestHistory.getCommitHash())) {
                log.debug("Commit hash {} matches the latest analysis for URL {}", currentCommitHash, input.getRepoUrl());
                if (existingSchemaPathStr != null && !existingSchemaPathStr.isBlank()) {
                    try {
                        Path existingSchemaPath = Paths.get(existingSchemaPathStr);
                        if (Files.exists(existingSchemaPath) && Files.isRegularFile(existingSchemaPath)) {
                            needsAnalysis = false;
                            userQueryContextHistoryId.put(appUser.getId(), latestHistory.getId());
                            sendMessage(chatId, "✅ This repository version \\(" + escapedValidatedUrl + "\\) was already analyzed\\. Query context set\\.\nSchema available at: `" + escapeMarkdownV2(existingSchemaPath.toString()) + "`");
                            userStates.remove(userId);
                            analysisInputState.remove(userId);
                        } else {
                            reason = "Previous result file missing at: " + existingSchemaPathStr;
                            log.warn(reason);
                        }
                    } catch (Exception e) {
                        reason = "Invalid stored schema path: " + existingSchemaPathStr;
                        log.error(reason, e);
                    }
                } else {
                    reason = "Schema file path missing in the latest history record.";
                    log.warn(reason);
                }
            } else {
                reason = "Repository has been updated or commit hash mismatch. Latest analyzed: " + latestHistory.getCommitHash() + ", Current: " + currentCommitHash;
                log.info(reason);
            }

            if (needsAnalysis && latestHistory.hasCredentials()) {
                log.info("Re-analysis needed for {}. Reusing credentials from history ID {}", input.getRepoUrl(), latestHistory.getId());
                input.setCredentials(DbCredentialsInput.fromHistory(latestHistory));
                needsCredentials = false;
            } else if (needsAnalysis) {
                reason += (reason.isEmpty() ? "" : " Also,") + " needs new credentials or previous credentials were not found.";
            }
        } else {
            reason = "New repository URL for this user.";
            log.info(reason);
        }

        if (needsAnalysis) {
            log.info("Proceeding with analysis for URL {}. Reason: {}", input.getRepoUrl(), reason);
            if (needsCredentials) {
                userStates.put(userId, UserState.WAITING_FOR_DB_HOST);
                AnalysisInputState currentInputState = analysisInputState.computeIfAbsent(userId, k -> new AnalysisInputState());
                currentInputState.setRepoUrl(input.getRepoUrl());
                currentInputState.setCommitHash(input.getCommitHash());
                currentInputState.setCredentials(new DbCredentialsInput());
                sendMessage(chatId, "Analysis required for `" + escapedValidatedUrl + "`\\. " +
                        (reason.isEmpty() ? "" : "_(" + escapeMarkdownV2(reason) + ")_ ") +
                        "Please provide DB credentials\\.\nEnter DB **hostname** or **IP**:");
            } else {
                log.info("Proceeding directly to analysis for {} using reused credentials.", input.getRepoUrl());
                performAnalysis(chatId, userId, appUser, input);
                userStates.remove(userId);
                analysisInputState.remove(userId);
            }
        }
    }

    private void handleCredentialsInput(long chatId, long userId, AppUser appUser, String text, UserState currentState) {
        DbCredentialsInput credsInputDirect = credentialsInputState.get(userId);
        AnalysisInputState analysisInputFlow = analysisInputState.get(userId);
        DbCredentialsInput currentCreds = (analysisInputFlow != null && analysisInputFlow.getCredentials() != null)
                ? analysisInputFlow.getCredentials()
                : credsInputDirect;

        if (currentCreds == null) {
            log.error("Input state missing for user {}, state {}", userId, currentState);
            userStates.remove(userId); credentialsInputState.remove(userId); analysisInputState.remove(userId);
            sendMessage(chatId, "Something went wrong\\. Please start again\\.");
            return;
        }

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

                    if (analysisInputFlow != null && analysisInputFlow.getRepoUrl() != null) {
                        log.info("Proceeding to analysis after credential input for /analyze_repo flow.");
                        performAnalysis(chatId, userId, appUser, analysisInputFlow);
                    } else if (credsInputDirect != null && credsInputDirect.getAssociatedRepoUrl() != null) {
                        log.info("Processing credentials for /set_db_credentials flow.");
                        Optional<AnalysisHistory> historyOpt = analysisHistoryRepository.findFirstByAppUserAndRepositoryUrlOrderByAnalyzedAtDesc(appUser, credsInputDirect.getAssociatedRepoUrl());
                        if (historyOpt.isPresent()) {
                            AnalysisHistory history = historyOpt.get();
                            history.setDbHost(currentCreds.getHost());
                            history.setDbPort(currentCreds.getPort());
                            history.setDbName(currentCreds.getName());
                            history.setDbUser(currentCreds.getUsername());
                            history.setDbPasswordEncrypted(currentCreds.getEncryptedPassword());
                            analysisHistoryRepository.save(history);
                            log.info("Updated credentials for existing analysis history ID {}", history.getId());
                            sendMessage(chatId, "✅ Database credentials updated successfully for `" + escapeMarkdownV2(credsInputDirect.getAssociatedRepoUrl()) + "`\\!");
                        } else {
                            log.warn("Cannot update credentials. No analysis history found for URL: {}", credsInputDirect.getAssociatedRepoUrl());
                            sendMessage(chatId, "⚠️ Could not find a previous analysis for `" + escapeMarkdownV2(credsInputDirect.getAssociatedRepoUrl()) + "`\\. Credentials not saved\\. Please run `/analyze_repo` first for this repository\\.");
                        }
                    } else {
                        log.error("Cannot determine flow after password input for user {}. No active analysis or direct credential setting flow.", userId);
                        sendMessage(chatId, "An internal error occurred\\. Please try again\\.");
                    }
                    userStates.remove(userId);
                    credentialsInputState.remove(userId);
                    analysisInputState.remove(userId);
                    break;
                default:
                    log.warn("Unexpected state {} during credential input.", currentState);
                    userStates.remove(userId); credentialsInputState.remove(userId); analysisInputState.remove(userId);
            }
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Invalid port number\\. Please enter a valid number\\.");
        } catch (Exception e) {
            log.error("Error processing credential input", e);
            userStates.remove(userId); credentialsInputState.remove(userId); analysisInputState.remove(userId);
            sendMessage(chatId, "An error occurred\\. Please try again\\.");
        }
    }

    private void performAnalysis(long chatId, long userId, AppUser appUser, AnalysisInputState input) {
        String validatedUrl = input.getRepoUrl();
        String commitHashToSave = input.getCommitHash();
        DbCredentialsInput credentials = input.getCredentials();
        if (credentials == null || credentials.getEncryptedPassword() == null) {
            log.error("Credentials missing in performAnalysis for user {}.", userId);
            sendMessage(chatId, "Error: DB credentials missing for analysis\\.");
            return;
        }

        String escapedUrl = escapeMarkdownV2(validatedUrl);
        String escapedHash = escapeMarkdownV2(commitHashToSave);
        String versionPart = commitHashToSave.equals("UNKNOWN_COMMIT") ? "" : " \\(version: `" + escapedHash.substring(0, Math.min(7, escapedHash.length())) + "`\\)";
        sendMessage(chatId, "⏳ Starting analysis for " + escapedUrl + versionPart + "\\.\\.\\. This may take a while\\.");

        taskExecutor.submit(() -> {
            MDC.put("telegramId", appUser.getTelegramId());
            Path resultSchemaPath = null;
            String oldSchemaPathToDelete = null;
            try {
                List<AnalysisHistory> oldHistories = analysisHistoryRepository.findByAppUserAndRepositoryUrl(appUser, validatedUrl);
                oldSchemaPathToDelete = oldHistories.stream()
                        .max(Comparator.comparing(AnalysisHistory::getAnalyzedAt))
                        .map(AnalysisHistory::getSchemaFilePath)
                        .orElse(null);

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

                    if (!oldHistories.isEmpty()) {
                        analysisHistoryRepository.deleteAll(oldHistories);
                        log.info("Deleted {} old history records for URL {}.", oldHistories.size(), validatedUrl);
                    }

                    if (oldSchemaPathToDelete != null && !oldSchemaPathToDelete.isBlank() && !oldSchemaPathToDelete.equals(resultSchemaPath.toString())) {
                        try {
                            if (Files.deleteIfExists(Paths.get(oldSchemaPathToDelete))) {
                                log.info("Deleted previous schema file: {}", oldSchemaPathToDelete);
                            }
                        } catch (Exception eDel) {
                            log.error("Failed to delete previous schema file: {}", oldSchemaPathToDelete, eDel);
                        }
                    }
                    userQueryContextHistoryId.put(appUser.getId(), savedHistory.getId());
                    sendMessage(chatId, "✅ Analysis successful\\! Schema saved\\.\nRepository: " + escapedUrl + "\nQuery context automatically set\\.\nUse `/query <your question>`");
                } catch (Exception dbEx) {
                    log.error("Failed to save/cleanup analysis results in DB", dbEx);
                    sendMessage(chatId, "⚠️ Analysis done, but failed to save results/cleanup database records\\.");
                }
            } catch (Exception analysisEx) {
                log.error("Analysis failed for URL {}", validatedUrl, analysisEx);
                String reasonMsg = analysisEx.getMessage();
                if (analysisEx.getCause() != null) {
                    reasonMsg += " \\(Cause: " + analysisEx.getCause().getMessage() + "\\)";
                }
                sendMessage(chatId, "❌ Analysis failed for repository: " + escapedUrl + "\nReason: " + escapeMarkdownV2(reasonMsg));
            } finally {
                MDC.remove("telegramId");
            }
        });
    }

    private void handleQueryCommand(long chatId, AppUser appUser, String queryText) {
        log.info("Received /query command with text: '{}'", queryText);
        processUserQuery(chatId, appUser, queryText);
    }

    private void handleQueryInput(long chatId, long userId, AppUser appUser, String queryText) {
        log.info("Received query text while waiting for user {}: '{}'", userId, queryText);
        userStates.remove(userId);
        processUserQuery(chatId, appUser, queryText);
    }

    private void processUserQuery(long chatId, AppUser appUser, String userQuery) {
        String outputFormat = "text";
        String queryForLlm = userQuery.trim();
        String originalQueryForLog = userQuery;

        if (queryForLlm.toLowerCase().endsWith(" " + CSV_FLAG)) {
            outputFormat = "csv";
            queryForLlm = queryForLlm.substring(0, queryForLlm.length() - (CSV_FLAG.length() + 1) ).trim();
        } else if (queryForLlm.toLowerCase().endsWith(" " + TXT_FLAG)) {
            outputFormat = "txt";
            queryForLlm = queryForLlm.substring(0, queryForLlm.length() - (TXT_FLAG.length() + 1) ).trim();
        }

        if (queryForLlm.isEmpty()) {
            sendMessage(chatId, "Query text cannot be empty, even after removing flags\\.");
            return;
        }
        log.info("Processing user query '{}'. Query for LLM: '{}'. Output format: {}", originalQueryForLog, queryForLlm, outputFormat);

        Long targetHistoryId = userQueryContextHistoryId.get(appUser.getId());
        Optional<AnalysisHistory> targetHistoryOpt;
        String contextMessage;

        if (targetHistoryId != null) {
            targetHistoryOpt = analysisHistoryRepository.findById(targetHistoryId);
            contextMessage = targetHistoryOpt.map(h -> "based on the schema for `" + escapeMarkdownV2(h.getRepositoryUrl()) + "`").orElse("based on a previously set context \\(not found\\?\\)");
            if (targetHistoryOpt.isEmpty()) { sendMessage(chatId, "Context error\\. Please use `/list_schemas` and `/use_schema` again\\."); userQueryContextHistoryId.remove(appUser.getId()); return; }
        } else {
            targetHistoryOpt = analysisHistoryRepository.findFirstByAppUserOrderByAnalyzedAtDesc(appUser);
            if (targetHistoryOpt.isEmpty()) { sendMessage(chatId, "You haven't analyzed any repository yet\\. Please use `/analyze_repo` first\\."); return; }
            contextMessage = "based on the *latest* schema analyzed \\(`" + escapeMarkdownV2(targetHistoryOpt.get().getRepositoryUrl()) + "`\\)";
            targetHistoryId = targetHistoryOpt.get().getId();
        }
        AnalysisHistory targetHistory = targetHistoryOpt.get();
        String schemaPathStr = targetHistory.getSchemaFilePath();
        String commitHash = targetHistory.getCommitHash();
        Path schemaPath; String schemaJson;
        try {
            schemaPath = Paths.get(schemaPathStr);
            if (!Files.exists(schemaPath) || !Files.isRegularFile(schemaPath)) {
                sendMessage(chatId, "Error: The schema file for `" + escapeMarkdownV2(targetHistory.getRepositoryUrl()) + "` is missing\\. Please run `/analyze_repo` again\\."); return;
            }
            schemaJson = Files.readString(schemaPath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Error accessing schema file: {}", schemaPathStr, e);
            sendMessage(chatId, "An error occurred while accessing the schema file \\(" + escapeMarkdownV2(e.getClass().getSimpleName()) + "\\)\\. Please try re\\-analyzing\\."); return;
        }

        String notification = "🧠 Got it\\! Asking the AI " + contextMessage + " \\(`" + escapeMarkdownV2(commitHash).substring(0, 7) + "`\\)\\.";
        if ("csv".equals(outputFormat)) {
            notification += "\n_Output will be in CSV file format\\._";
        } else if ("txt".equals(outputFormat)) {
            notification += "\n_Output will be in TXT file format\\._";
        }
        sendMessage(chatId, notification + "\\.\\.\\.");

        final Long finalTargetHistoryId = targetHistoryId;
        final long currentUserId = appUser.getId();
        final String finalOutputFormat = outputFormat;
        final String finalQueryForLlm = queryForLlm;

        taskExecutor.submit(() -> {
            MDC.put("telegramId", appUser.getTelegramId());
            try {
                Optional<String> generatedSqlOpt = geminiService.generateSqlFromSchema(schemaJson, finalQueryForLlm);
                if (generatedSqlOpt.isPresent()) {
                    String sql = generatedSqlOpt.get();
                    log.info("Generated SQL for History ID: {}. Output format: {}. SQL: {}", finalTargetHistoryId, finalOutputFormat, sql);
                    String escapedSql = sql.replace("\\", "\\\\").replace("`", "\\`");
                    lastGeneratedSql.put(currentUserId, finalTargetHistoryId + ":" + sql);

                    String callbackButtonData = String.format("execute_sql:%d:%s", finalTargetHistoryId, finalOutputFormat);

                    InlineKeyboardButton executeButton = InlineKeyboardButton.builder().text("🚀 Execute Query").callbackData(callbackButtonData).build();
                    InlineKeyboardMarkup keyboardMarkup = InlineKeyboardMarkup.builder().keyboardRow(List.of(executeButton)).build();
                    SendMessage resultMessage = SendMessage.builder().chatId(chatId)
                            .text("🤖 Generated SQL query:\n\n`" + escapedSql + "`\n\n*Disclaimer:* Review before execution\\.\nPress button to run\\.")
                            .parseMode("MarkdownV2").replyMarkup(keyboardMarkup).build();
                    execute(resultMessage);
                } else {
                    sendMessage(chatId, "Sorry, I couldn't generate an SQL query\\. Try rephrasing\\.");
                }
            } catch (Exception e) {
                log.error("Error during Gemini SQL generation", e);
                sendMessage(chatId, "An error occurred during SQL generation\\.");
            } finally { MDC.remove("telegramId"); }
        });
    }

    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        String callbackData = callbackQuery.getData();
        long chatId = callbackQuery.getMessage().getChatId();
        long userId = callbackQuery.getFrom().getId();
        String telegramIdStr = String.valueOf(userId);
        String callbackQueryId = callbackQuery.getId();

        MDC.put("telegramId", telegramIdStr);
        String answerText = "Processing...";

        try {
            log.info("Received callback query data: {}", callbackData);
            if (callbackData != null && callbackData.startsWith("execute_sql:")) {
                String[] parts = callbackData.split(":");
                if (parts.length != 3) {
                    answerText = "Error: Invalid action format";
                    sendAnswerCallbackQuery(callbackQueryId, answerText, true); return;
                }
                long historyIdToExecute;
                try {
                    historyIdToExecute = Long.parseLong(parts[1]);
                } catch (NumberFormatException e) {
                    answerText = "Error: Invalid History ID in action";
                    sendAnswerCallbackQuery(callbackQueryId, answerText, true); return;
                }
                String format = parts[2];

                AppUser appUser = findOrCreateUser(telegramIdStr);
                if (appUser == null) { answerText = "Error: User not found"; sendAnswerCallbackQuery(callbackQueryId, answerText, true); return; }

                String storedData = lastGeneratedSql.get(appUser.getId());
                String sqlToExecute = null;
                if (storedData != null && storedData.startsWith(historyIdToExecute + ":")) {
                    sqlToExecute = storedData.substring(String.valueOf(historyIdToExecute).length() + 1);
                }

                if (sqlToExecute != null) {
                    Optional<AnalysisHistory> historyOpt = analysisHistoryRepository.findById(historyIdToExecute);
                    if (historyOpt.isPresent() && Objects.equals(historyOpt.get().getAppUser().getId(), appUser.getId())) {
                        AnalysisHistory history = historyOpt.get();
                        if (history.hasCredentials()) {
                            answerText = "Execution started...";
                            sendMessage(chatId, "🚀 Executing SQL query\\.\\.\\.");
                            lastGeneratedSql.remove(appUser.getId());

                            final String finalSql = sqlToExecute;
                            final String finalFormat = format;

                            taskExecutor.submit(() -> {
                                MDC.put("telegramId", telegramIdStr);
                                try {
                                    DynamicQueryExecutorService.QueryResult result = queryExecutorService.executeQuery(
                                            history.getDbHost(), history.getDbPort(), history.getDbName(),
                                            history.getDbUser(), history.getDbPasswordEncrypted(), finalSql);
                                    if (result.isSuccess()) {
                                        if (result.type() == DynamicQueryExecutorService.QueryType.SELECT) {
                                            List<Map<String, Object>> resultData = (List<Map<String, Object>>) result.data();
                                            if ("csv".equals(finalFormat)) {
                                                sendSelectResultAsCsvFile(chatId, resultData, history.getRepositoryUrl());
                                            } else if ("txt".equals(finalFormat)) {
                                                sendSelectResultAsTxtFile(chatId, resultData, history.getRepositoryUrl());
                                            } else {
                                                sendSelectResultAsTextInChat(chatId, resultData, history.getRepositoryUrl());
                                            }
                                        } else if (result.type() == DynamicQueryExecutorService.QueryType.UPDATE) {
                                            sendMessage(chatId, "✅ Query executed successfully\\. Rows affected: " + result.data());
                                        } else {
                                            sendMessage(chatId, "✅ Query executed, unknown result type\\.");
                                        }
                                    } else {
                                        sendMessage(chatId, "❌ Query execution failed: " + escapeMarkdownV2(result.errorMessage()));
                                    }
                                } catch (Exception e) {
                                    log.error("Error executing SQL query '{}'", finalSql, e);
                                    sendMessage(chatId, "An unexpected error occurred during SQL execution\\.");
                                } finally { MDC.remove("telegramId"); }
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
                    answerText = "❌ Error: Query expired or invalid!";
                    sendMessage(chatId, "Error: Could not find the SQL query to execute\\. Please generate it again using `/query`\\.");
                }
            } else {
                log.warn("Received unknown callback data: {}", callbackData);
                answerText = "Unknown action";
            }
        } catch (Exception e) {
            log.error("Error processing callback query", e);
            answerText = "Error processing action";
        } finally {
            MDC.remove("telegramId");
            sendAnswerCallbackQuery(callbackQueryId, answerText, answerText.startsWith("❌ Error:"));
        }
    }

    private void sendAnswerCallbackQuery(String callbackQueryId, String text, boolean showAlert) {
        AnswerCallbackQuery answer = AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId).text(text).showAlert(showAlert).build();
        try { execute(answer); } catch (TelegramApiException e) { log.error("Failed to answer callback query", e); }
    }

    private void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setParseMode("MarkdownV2");
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send MarkdownV2 message to chat ID {}: {}. Retrying as plain text.", chatId, e.getMessage());
            message.setParseMode(null);
            try {
                execute(message);
            } catch (TelegramApiException ex) {
                log.error("Failed to send plain text message to chat ID {}: {}", chatId, ex.getMessage());
            }
        }
    }

    private String escapeMarkdownV2(String text) {
        if (text == null) return "";
        return text.replaceAll("([_*~`>\\[\\]()#\\+\\-=|{}.!-])", "\\\\$1");
    }

    private enum UserState {
        IDLE, WAITING_FOR_REPO_URL, WAITING_FOR_LLM_QUERY,
        WAITING_FOR_REPO_URL_FOR_CREDS,
        WAITING_FOR_DB_HOST, WAITING_FOR_DB_PORT, WAITING_FOR_DB_NAME,
        WAITING_FOR_DB_USER, WAITING_FOR_DB_PASSWORD;
        public boolean isCredentialInputState() {
            return this == WAITING_FOR_DB_HOST || this == WAITING_FOR_DB_PORT || this == WAITING_FOR_DB_NAME || this == WAITING_FOR_DB_USER || this == WAITING_FOR_DB_PASSWORD;
        }
    }

    @Data
    private static class AnalysisInputState {
        private String repoUrl;
        private String commitHash;
        private DbCredentialsInput credentials;
    }

    @Data
    private static class DbCredentialsInput {
        private String host;
        private Integer port;
        private String name;
        private String username;
        private String encryptedPassword;
        private String associatedRepoUrl;
        public static DbCredentialsInput fromHistory(AnalysisHistory h) {
            if (!h.hasCredentials()) return null;
            DbCredentialsInput i = new DbCredentialsInput();
            i.host = h.getDbHost();
            i.port = h.getDbPort();
            i.name = h.getDbName();
            i.username = h.getDbUser();
            i.encryptedPassword = h.getDbPasswordEncrypted();
            return i;
        }
    }

    private void sendSelectResultAsTextInChat(long chatId, List<Map<String, Object>> rows, String repoUrlForFilename) {
        if (rows == null || rows.isEmpty()) {
            sendMessage(chatId, "✅ Query executed successfully, but it returned no rows\\.");
            return;
        }

        List<String> headers = new ArrayList<>(rows.getFirst().keySet());

        if (headers.size() > MAX_COLUMNS_FOR_CHAT_TEXT) {
            sendMessage(chatId, "ℹ️ The result table has too many columns \\(" + headers.size() +
                    "\\) for chat display\\.\nSwitching to TXT file output\\.");
            sendSelectResultAsTxtFile(chatId, rows, repoUrlForFilename);
            return;
        }

        final int MAX_CELL_CONTENT_DISPLAY_LENGTH_CHAT = 30;
        final String TRUNCATION_MARKER = "...";
        final int TRUNCATION_MARKER_LEN = TRUNCATION_MARKER.length();

        Map<String, Integer> columnWidths = new HashMap<>();
        Map<String, String> processedHeaders = new HashMap<>();

        for (String headerName : headers) {
            String displayHeader = headerName;
            if (headerName.length() > MAX_CELL_CONTENT_DISPLAY_LENGTH_CHAT) {
                displayHeader = headerName.substring(0, MAX_CELL_CONTENT_DISPLAY_LENGTH_CHAT - TRUNCATION_MARKER_LEN) + TRUNCATION_MARKER;
            }
            processedHeaders.put(headerName, displayHeader);
            columnWidths.put(headerName, displayHeader.length());
        }

        int dataRowDisplayLimit = Math.min(rows.size(), MAX_SELECT_ROWS_FOR_CHAT_TEXT);
        List<List<String>> processedDataRows = new ArrayList<>();

        for (int i = 0; i < dataRowDisplayLimit; i++) {
            Map<String, Object> row = rows.get(i);
            List<String> processedRow = new ArrayList<>();
            for (String headerName : headers) {
                Object value = row.get(headerName);
                String cellText = (value != null ? value.toString() : "NULL");
                cellText = cellText.replace("\n", " ").replace("\r", " ");

                String displayCellText = cellText;
                if (cellText.length() > MAX_CELL_CONTENT_DISPLAY_LENGTH_CHAT) {
                    displayCellText = cellText.substring(0, MAX_CELL_CONTENT_DISPLAY_LENGTH_CHAT - TRUNCATION_MARKER_LEN) + TRUNCATION_MARKER;
                }
                processedRow.add(displayCellText);
                columnWidths.put(headerName, Math.max(columnWidths.get(headerName), displayCellText.length()));
            }
            processedDataRows.add(processedRow);
        }

        StringBuilder formattedTable = new StringBuilder();
        String columnSeparator = " | ";
        String headerLineSeparatorPart = "-|-";

        // Format headers row
        for (int i = 0; i < headers.size(); i++) {
            String headerName = headers.get(i);
            String displayHeader = processedHeaders.get(headerName);
            int width = columnWidths.get(headerName);
            formattedTable.append(String.format("%-" + width + "s", displayHeader));
            if (i < headers.size() - 1) formattedTable.append(columnSeparator);
        }
        formattedTable.append("\n");

        // Format separator line
        for (int i = 0; i < headers.size(); i++) {
            int width = columnWidths.get(headers.get(i));
            formattedTable.append("-".repeat(width));
            if (i < headers.size() - 1) formattedTable.append(headerLineSeparatorPart);
        }
        formattedTable.append("\n");

        // Format data rows
        for (List<String> rowData : processedDataRows) {
            for (int i = 0; i < headers.size(); i++) {
                String displayCellText = rowData.get(i);
                int width = columnWidths.get(headers.get(i));
                formattedTable.append(String.format("%-" + width + "s", displayCellText));
                if (i < headers.size() - 1) formattedTable.append(columnSeparator);
            }
            formattedTable.append("\n");
        }

        String tableContent = formattedTable.toString().trim();

        String firstLineForCheck = "";
        if (tableContent.contains("\n")) {
            firstLineForCheck = tableContent.substring(0, tableContent.indexOf("\n"));
        } else {
            firstLineForCheck = tableContent;
        }
        int approxTableWidth = firstLineForCheck.length();
        int maxChatTableWidth = 150;

        if (approxTableWidth > maxChatTableWidth && dataRowDisplayLimit > 1) { // Если все еще широкая (и не одна строка)
            sendMessage(chatId, "ℹ️ The result table is still quite wide for chat display, even with " + headers.size() + " columns\\. Switching to TXT file output\\.");
            sendSelectResultAsTxtFile(chatId, rows, repoUrlForFilename); // Автоматический переход на TXT
            return;
        }

        String messagePrefix = "✅ Query executed successfully\\. Results" +
                (rows.size() > dataRowDisplayLimit ? " \\(showing first " + dataRowDisplayLimit + " of " + rows.size() + " rows\\):" : ":") +
                "\n\n";
        String fullMessage = messagePrefix + "```\n" + tableContent + "\n```";

        int telegramMaxLen = 4096;
        if (fullMessage.length() <= telegramMaxLen) {
            sendMessage(chatId, fullMessage);
        } else {
            sendMessage(chatId, "ℹ️ The result is too long to display directly in chat, even after truncating rows\\. Switching to TXT file output\\.");
            sendSelectResultAsTxtFile(chatId, rows, repoUrlForFilename); // Автоматический переход на TXT
        }
    }

    private String escapeCsvField(String data) {
        if (data == null) return "";
        if (data.contains(",") || data.contains("\"") || data.contains("\n") || data.contains("\r")) {
            return "\"" + data.replace("\"", "\"\"") + "\"";
        }
        return data;
    }

    private void sendSelectResultAsCsvFile(long chatId, List<Map<String, Object>> rows, String repoUrlForFilename) {
        if (rows == null || rows.isEmpty()) {
            sendMessage(chatId, "✅ Query executed successfully, but it returned no rows (nothing to put in CSV)\\.");
            return;
        }
        List<String> headers = new ArrayList<>(rows.getFirst().keySet());
        StringBuilder csvContent = new StringBuilder();
        csvContent.append(headers.stream().map(this::escapeCsvField).collect(Collectors.joining(","))).append("\n");
        for (Map<String, Object> row : rows) {
            csvContent.append(headers.stream()
                            .map(header -> row.get(header)).map(value -> (value != null ? value.toString() : ""))
                            .map(this::escapeCsvField).collect(Collectors.joining(",")))
                    .append("\n");
        }
        Path tempFile = null;
        try {
            String safeRepoPart = "query_results";
            if (repoUrlForFilename != null && !repoUrlForFilename.isBlank()) {
                String[] urlParts = repoUrlForFilename.split("/");
                if (urlParts.length > 0) {
                    safeRepoPart = urlParts[urlParts.length - 1].replaceAll("[^a-zA-Z0-9.\\-_]", "_").replaceAll("\\.git$", "");
                }
            }
            safeRepoPart = safeRepoPart.length() > 30 ? safeRepoPart.substring(0, 30) : safeRepoPart;
            String filename = safeRepoPart + "_" + System.currentTimeMillis() + ".csv";

            tempFile = Files.createTempFile("cq_csv_", ".csv");
            Files.writeString(tempFile, csvContent.toString(), StandardCharsets.UTF_8);
            SendDocument sendDocumentRequest = new SendDocument();
            sendDocumentRequest.setChatId(String.valueOf(chatId));
            sendDocumentRequest.setDocument(new InputFile(tempFile.toFile(), filename));
            sendDocumentRequest.setCaption("✅ Query executed successfully. Results are in the attached CSV file.");
            execute(sendDocumentRequest);
        } catch (IOException | TelegramApiException e) {
            log.error("Failed to send results as CSV file to chat ID {}", chatId, e);
            if (e instanceof IOException) sendMessage(chatId, "❌ Error: Could not generate CSV results file.");
        } finally {
            if (tempFile != null) try { Files.deleteIfExists(tempFile); } catch (IOException e) { log.warn("Failed to delete temp CSV file", e); }
        }
    }

    private void sendSelectResultAsTxtFile(long chatId, List<Map<String, Object>> rows, String repoUrlForFilename) {
        if (rows == null || rows.isEmpty()) {
            sendMessage(chatId, "✅ Query executed successfully, but it returned no rows (nothing to put in TXT file)\\.");
            return;
        }

        final int MAX_CELL_CONTENT_DISPLAY_LENGTH_FILE = 60;
        final String TRUNCATION_MARKER = "...";
        final int TRUNCATION_MARKER_LEN = TRUNCATION_MARKER.length();

        List<String> headers = new ArrayList<>(rows.getFirst().keySet());
        Map<String, Integer> columnWidths = new HashMap<>();
        Map<String, String> processedHeaders = new HashMap<>();

        for (String headerName : headers) {
            String displayHeader = headerName;
            if (headerName.length() > MAX_CELL_CONTENT_DISPLAY_LENGTH_FILE) {
                displayHeader = headerName.substring(0, MAX_CELL_CONTENT_DISPLAY_LENGTH_FILE - TRUNCATION_MARKER_LEN) + TRUNCATION_MARKER;
            }
            processedHeaders.put(headerName, displayHeader);
            columnWidths.put(headerName, displayHeader.length());
        }

        List<List<String>> processedDataRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<String> processedRow = new ArrayList<>();
            for (String headerName : headers) {
                Object value = row.get(headerName);
                String cellText = (value != null ? value.toString() : "NULL");
                cellText = cellText.replace("\n", " ").replace("\r", " ");

                String displayCellText = cellText;
                if (cellText.length() > MAX_CELL_CONTENT_DISPLAY_LENGTH_FILE) {
                    displayCellText = cellText.substring(0, MAX_CELL_CONTENT_DISPLAY_LENGTH_FILE - TRUNCATION_MARKER_LEN) + TRUNCATION_MARKER;
                }
                processedRow.add(displayCellText);
                columnWidths.put(headerName, Math.max(columnWidths.get(headerName), displayCellText.length()));
            }
            processedDataRows.add(processedRow);
        }

        StringBuilder formattedTable = new StringBuilder();
        String columnSeparator = " | ";
        String headerLineSeparatorPart = "-|-";

        for (int i = 0; i < headers.size(); i++) {
            String headerName = headers.get(i);
            String displayHeader = processedHeaders.get(headerName);
            int width = columnWidths.get(headerName);
            formattedTable.append(String.format("%-" + width + "s", displayHeader));
            if (i < headers.size() - 1) formattedTable.append(columnSeparator);
        }
        formattedTable.append("\n");
        for (int i = 0; i < headers.size(); i++) {
            int width = columnWidths.get(headers.get(i));
            formattedTable.append("-".repeat(width));
            if (i < headers.size() - 1) formattedTable.append(headerLineSeparatorPart);
        }
        formattedTable.append("\n");
        for (List<String> rowData : processedDataRows) {
            for (int i = 0; i < headers.size(); i++) {
                String displayCellText = rowData.get(i);
                int width = columnWidths.get(headers.get(i));
                formattedTable.append(String.format("%-" + width + "s", displayCellText));
                if (i < headers.size() - 1) formattedTable.append(columnSeparator);
            }
            formattedTable.append("\n");
        }

        String tableContentForFile = formattedTable.toString().trim();
        Path tempFile = null;
        try {
            String safeRepoPart = "query_results";
            if (repoUrlForFilename != null && !repoUrlForFilename.isBlank()) {
                String[] urlParts = repoUrlForFilename.split("/");
                if (urlParts.length > 0) {
                    safeRepoPart = urlParts[urlParts.length - 1].replaceAll("[^a-zA-Z0-9.\\-_]", "_").replaceAll("\\.git$", "");
                }
            }
            safeRepoPart = safeRepoPart.length() > 30 ? safeRepoPart.substring(0, 30) : safeRepoPart;
            String filename = safeRepoPart + "_" + System.currentTimeMillis() + ".txt";

            tempFile = Files.createTempFile("cq_txt_", ".txt");
            Files.writeString(tempFile, tableContentForFile, StandardCharsets.UTF_8);
            SendDocument sendDocumentRequest = new SendDocument();
            sendDocumentRequest.setChatId(String.valueOf(chatId));
            sendDocumentRequest.setDocument(new InputFile(tempFile.toFile(), filename));
            sendDocumentRequest.setCaption("✅ Query executed successfully. Results are in the attached TXT file.");
            execute(sendDocumentRequest);
        } catch (IOException | TelegramApiException e) {
            log.error("Failed to send results as TXT file to chat ID {}", chatId, e);
            if (e instanceof IOException) sendMessage(chatId, "❌ Error: Could not generate TXT results file.");
        } finally {
            if (tempFile != null) try { Files.deleteIfExists(tempFile); } catch (IOException e) { log.warn("Failed to delete temp TXT file", e); }
        }
    }

}