package com.example.cognitivequery.bot;

import com.example.cognitivequery.model.AnalysisHistory;
import com.example.cognitivequery.model.AppUser;
import com.example.cognitivequery.model.ScheduledQuery;
import com.example.cognitivequery.repository.AnalysisHistoryRepository;
import com.example.cognitivequery.repository.ScheduledQueryRepository;
import com.example.cognitivequery.repository.UserRepository;
import com.example.cognitivequery.service.ScheduledQueryExecutionService;
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
import org.springframework.scheduling.support.CronExpression;
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
import java.time.LocalDateTime;
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

    private final ScheduledQueryRepository scheduledQueryRepository; // НОВЫЙ
    private final ScheduledQueryExecutionService scheduledQueryExecutionService; // НОВЫЙ

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
            WebClient.Builder webClientBuilder,
            ScheduledQueryRepository scheduledQueryRepository, // НОВЫЙ
            ScheduledQueryExecutionService scheduledQueryExecutionService // НОВЫЙ
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
        this.scheduledQueryRepository = scheduledQueryRepository; // НОВЫЙ
        this.scheduledQueryExecutionService = scheduledQueryExecutionService; // НОВЫЙ
        this.scheduledQueryExecutionService.setTelegramBot(this); // Передаем ссылку на бота в сервис
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
        commands.add(new BotCommand("schedule_query", "Create a new scheduled query"));
        commands.add(new BotCommand("list_scheduled_queries", "List your scheduled queries"));
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
//here
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
            // *** НОВОЕ: Обработка состояний для /schedule_query ***
            else if (currentState.isScheduleCreationState()) {
                if (!messageText.startsWith("/")) {
                    handleScheduleCreationInput(chatId, userId, appUser, messageText, currentState);
                    processed = true;
                } else { // Command received, cancel schedule creation
                    userStates.remove(userId); scheduleCreationStates.remove(userId);
                    sendMessage(chatId, "Schedule creation cancelled\\.");
                    log.warn("Command received, canceling schedule creation.");
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
                }
                // *** НОВЫЕ КОМАНДЫ ***
                else if (commandPart.equals("/schedule_query")) {
                    startScheduleQueryFlow(chatId, userId, appUser);
                    processed = true;
                } else if (commandPart.equals("/list_scheduled_queries")) {
                    handleListScheduledQueries(chatId, appUser);
                    processed = true;
                }
                else {
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

// В классе CognitiveQueryTelegramBot

    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        String callbackData = callbackQuery.getData();
        long chatId = callbackQuery.getMessage().getChatId();
        long userId = callbackQuery.getFrom().getId();
        String telegramIdStr = String.valueOf(userId);
        String callbackQueryId = callbackQuery.getId();

        MDC.put("telegramId", telegramIdStr);
        String answerText = "Processing..."; // Текст по умолчанию для всплывающего уведомления
        boolean showAlert = false; // Показывать ли уведомление как alert

        try {
            log.info("Received callback query data: {}", callbackData);
            AppUser appUser = findOrCreateUser(telegramIdStr);
            if (appUser == null) {
                answerText = "Error: User data not accessible.";
                showAlert = true;
                sendAnswerCallbackQuery(callbackQueryId, answerText, showAlert);
                return;
            }

            if (callbackData != null && callbackData.startsWith("execute_sql:")) {
                String[] parts = callbackData.split(":");
                if (parts.length != 3) { // Ожидаем execute_sql:historyId:format
                    log.error("Invalid callback data format for execute_sql: {}", callbackData);
                    answerText = "Error: Invalid action format";
                    showAlert = true;
                } else {
                    long historyIdToExecute;
                    try {
                        historyIdToExecute = Long.parseLong(parts[1]);
                    } catch (NumberFormatException e) {
                        log.error("Invalid history ID in execute_sql callback: {}", parts[1]);
                        answerText = "Error: Invalid History ID in action";
                        showAlert = true;
                        // Выходим здесь, так как без ID дальше некуда
                        sendAnswerCallbackQuery(callbackQueryId, answerText, showAlert);
                        return;
                    }
                    String format = parts[2]; // "text", "csv", or "txt"

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
                                answerText = "Execution started..."; // Для toast-уведомления
                                showAlert = false;
                                sendMessage(chatId, "🚀 Executing SQL query\\.\\.\\."); // Сообщение в чат
                                lastGeneratedSql.remove(appUser.getId());

                                final String finalSql = sqlToExecute;
                                final String finalFormat = format; // Для использования в лямбде
                                final String repoUrlForFile = history.getRepositoryUrl(); // Для имени файла

                                taskExecutor.submit(() -> {
                                    MDC.put("telegramId", telegramIdStr);
                                    try {
                                        DynamicQueryExecutorService.QueryResult result = queryExecutorService.executeQuery(
                                                history.getDbHost(), history.getDbPort(), history.getDbName(),
                                                history.getDbUser(), history.getDbPasswordEncrypted(), finalSql);
                                        if (result.isSuccess()) {
                                            if (result.type() == DynamicQueryExecutorService.QueryType.SELECT) {
                                                @SuppressWarnings("unchecked") // Безопасно, т.к. мы проверяем тип
                                                List<Map<String, Object>> resultData = (List<Map<String, Object>>) result.data();
                                                if ("csv".equals(finalFormat)) {
                                                    sendSelectResultAsCsvFile(chatId, resultData, repoUrlForFile);
                                                } else if ("txt".equals(finalFormat)) {
                                                    sendSelectResultAsTxtFile(chatId, resultData, repoUrlForFile);
                                                } else { // По умолчанию "text"
                                                    sendSelectResultAsTextInChat(chatId, resultData, repoUrlForFile);
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
                                    } finally {
                                        MDC.remove("telegramId");
                                    }
                                });
                            } else {
                                answerText = "❌ Error: DB credentials missing!";
                                showAlert = true;
                                sendMessage(chatId, "❌ Cannot execute: DB credentials missing for this analysis history\\. Use `/set_db_credentials`\\.");
                            }
                        } else {
                            answerText = "❌ Error: Invalid history!";
                            showAlert = true;
                            sendMessage(chatId, "❌ Cannot execute: Analysis history not found or invalid\\.");
                        }
                    } else {
                        answerText = "❌ Error: Query expired or invalid!";
                        showAlert = true;
                        sendMessage(chatId, "Error: Could not find the SQL query to execute\\. Please generate it again using `/query`\\.");
                    }
                }
            } else if (callbackData != null && callbackData.startsWith("sched_hist:")) {
                answerText = "History selected.";
                showAlert = false;
                String historyIdStrCallback = callbackData.substring("sched_hist:".length());
                // Вызываем соответствующий обработчик ввода для состояния создания расписания
                handleScheduleHistoryIdInput(chatId, userId, appUser, historyIdStrCallback);
            } else if (callbackData != null && callbackData.startsWith("sched_format:")) {
                answerText = "Format selected.";
                showAlert = false;
                String formatCallback = callbackData.substring("sched_format:".length());
                ScheduleCreationState state = scheduleCreationStates.get(userId);
                if (state != null) {
                    // Проверяем, что предыдущие шаги были завершены
                    if (state.getAnalysisHistoryId() == null || state.getSqlQuery() == null || state.getCronExpression() == null || state.getChatIdToNotify() == null) {
                        answerText = "Error: Previous steps not completed for scheduling.";
                        showAlert = true;
                        log.warn("User {} tried to set format for schedule via callback, but previous steps are missing. State: {}", userId, state);
                    } else {
                        state.setOutputFormat(formatCallback);
                        saveScheduledQuery(chatId, userId, appUser); // appUser уже получен в начале метода
                    }
                } else {
                    answerText = "Error: Schedule creation session expired or not found.";
                    showAlert = true;
                    log.warn("User {} tried to set format for schedule via callback, but no active schedule creation state found.", userId);
                }
            }
            // TODO: Добавить обработчики для кнопок управления расписаниями (пауза, возобновление, удаление)
            // Например:
            // else if (callbackData != null && callbackData.startsWith("pause_sched:")) { ... }
            // else if (callbackData != null && callbackData.startsWith("delete_sched:")) { ... }
            else {
                log.warn("Received unknown callback data: {}", callbackData);
                answerText = "Unknown action";
                showAlert = true;
            }
        } catch (Exception e) {
            log.error("Error processing callback query: " + callbackData, e);
            answerText = "Error processing action";
            showAlert = true;
        } finally {
            MDC.remove("telegramId");
            sendAnswerCallbackQuery(callbackQueryId, answerText, showAlert);
        }
    }

    private void sendAnswerCallbackQuery(String callbackQueryId, String text, boolean showAlert) {
        AnswerCallbackQuery answer = AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId).text(text).showAlert(showAlert).build();
        try { execute(answer); } catch (TelegramApiException e) { log.error("Failed to answer callback query", e); }
    }

    public void sendMessage(long chatId, String text) {
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

    public String escapeMarkdownV2(String text) {
        if (text == null) return "";
        return text.replaceAll("([_*~`>\\[\\]()#\\+\\-=|{}.!-])", "\\\\$1");
    }

    private enum UserState {
        IDLE, WAITING_FOR_REPO_URL, WAITING_FOR_LLM_QUERY,
        WAITING_FOR_REPO_URL_FOR_CREDS,
        WAITING_FOR_DB_HOST, WAITING_FOR_DB_PORT, WAITING_FOR_DB_NAME,
        WAITING_FOR_DB_USER, WAITING_FOR_DB_PASSWORD,
        WAITING_FOR_SCHEDULE_NAME,
        WAITING_FOR_SCHEDULE_HISTORY_CHOICE, // Если даем выбор из списка
        WAITING_FOR_SCHEDULE_HISTORY_ID,     // Если просим ввести ID
        WAITING_FOR_SCHEDULE_SQL,
        WAITING_FOR_SCHEDULE_CRON,
        WAITING_FOR_SCHEDULE_CHAT_ID,
        WAITING_FOR_SCHEDULE_OUTPUT_FORMAT;

        public boolean isCredentialInputState() {
            return this == WAITING_FOR_DB_HOST || this == WAITING_FOR_DB_PORT || this == WAITING_FOR_DB_NAME || this == WAITING_FOR_DB_USER || this == WAITING_FOR_DB_PASSWORD;
        }

        public boolean isScheduleCreationState() {
            return this == WAITING_FOR_SCHEDULE_NAME || this == WAITING_FOR_SCHEDULE_HISTORY_CHOICE ||
                    this == WAITING_FOR_SCHEDULE_HISTORY_ID || this == WAITING_FOR_SCHEDULE_SQL ||
                    this == WAITING_FOR_SCHEDULE_CRON || this == WAITING_FOR_SCHEDULE_CHAT_ID ||
                    this == WAITING_FOR_SCHEDULE_OUTPUT_FORMAT;
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

    public void sendSelectResultAsTextInChat(long chatId, List<Map<String, Object>> rows, String repoUrlForFilename) {
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

    public void sendSelectResultAsCsvFile(long chatId, List<Map<String, Object>> rows, String repoUrlForFilename) {
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

    public void sendSelectResultAsTxtFile(long chatId, List<Map<String, Object>> rows, String repoUrlForFilename) {
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

    @Data // Для хранения промежуточных данных при создании расписания
    private static class ScheduleCreationState {
        private String name;
        private Long analysisHistoryId;
        private AnalysisHistory analysisHistory; // Для быстрого доступа после выбора
        private String sqlQuery;
        private String cronExpression;
        private Long chatIdToNotify;
        private String outputFormat;
    }
    private final Map<Long, ScheduleCreationState> scheduleCreationStates = new ConcurrentHashMap<>();


    private void startScheduleQueryFlow(long chatId, long userId, AppUser appUser) {
        scheduleCreationStates.put(userId, new ScheduleCreationState());
        userStates.put(userId, UserState.WAITING_FOR_SCHEDULE_NAME);
        sendMessage(chatId, "Let's create a new scheduled query\\! \nFirst, please enter a descriptive **name** for this schedule \\(e\\.g\\., `Daily User Report`\\):");
    }

    private void handleScheduleNameInput(long chatId, long userId, AppUser appUser, String name) {
        ScheduleCreationState state = scheduleCreationStates.get(userId);
        if (state == null) {
            sendMessage(chatId, "Error: Schedule creation session expired or not found\\. Please start over with `/schedule_query`\\.");
            userStates.remove(userId);
            return;
        }
        if (name.trim().isEmpty()) {
            sendMessage(chatId, "Schedule name cannot be empty\\. Please enter a name:");
            return;
        }
        state.setName(name.trim());
        userStates.put(userId, UserState.WAITING_FOR_SCHEDULE_HISTORY_ID); // Меняем на это состояние

        List<AnalysisHistory> histories = analysisHistoryRepository.findByAppUserOrderByAnalyzedAtDesc(appUser);
        if (histories.isEmpty()) {
            sendMessage(chatId, "You have no analyzed repositories with set DB credentials\\. Please use `/analyze_repo` and `/set_db_credentials` first, then create a schedule\\.");
            userStates.remove(userId);
            scheduleCreationStates.remove(userId);
            return;
        }

        List<AnalysisHistory> historiesWithCreds = histories.stream()
                .filter(AnalysisHistory::hasCredentials)
                .collect(Collectors.toList());

        if (historiesWithCreds.isEmpty()) {
            sendMessage(chatId, "You have analyzed repositories, but none of them have DB credentials set up\\. Please use `/set_db_credentials` first, then create a schedule\\.");
            userStates.remove(userId);
            scheduleCreationStates.remove(userId);
            return;
        }

        StringBuilder sb = new StringBuilder("Great\\! Name set to: `" + escapeMarkdownV2(name.trim()) + "`\\.\n");
        sb.append("Please choose the **Analyzed Repository Schema** this query should run against, or type its ID:\n");

        var keyboardMarkupBuilder = InlineKeyboardMarkup.builder();
        int buttonCount = 0;
        final int MAX_BUTTONS_TO_SHOW = 5; // Показываем первые N для выбора кнопками

        for (AnalysisHistory h : historiesWithCreds) {
            if (buttonCount < MAX_BUTTONS_TO_SHOW) {
                // Формируем текст для кнопки, чтобы он был информативным и не слишком длинным
                String repoUrlDisplayName = h.getRepositoryUrl();
                // Пытаемся извлечь имя репозитория из URL
                try {
                    String[] parts = repoUrlDisplayName.split("/");
                    if (parts.length >= 2) {
                        repoUrlDisplayName = parts[parts.length - 2] + "/" + parts[parts.length - 1];
                    }
                } catch (Exception e) { /* оставляем полный URL если не получилось */ }

                repoUrlDisplayName = repoUrlDisplayName.length() > 35 ? "..." + repoUrlDisplayName.substring(repoUrlDisplayName.length() - 32) : repoUrlDisplayName;

                String buttonText = String.format("ID %d: %s (%.7s)", h.getId(), repoUrlDisplayName, h.getCommitHash());
                // Ограничиваем длину текста кнопки, если нужно (Telegram имеет лимиты)
                buttonText = buttonText.length() > 60 ? buttonText.substring(0, 57) + "..." : buttonText;

                keyboardMarkupBuilder.keyboardRow(List.of(InlineKeyboardButton.builder().text(buttonText).callbackData("sched_hist:" + h.getId()).build()));
                buttonCount++;
            } else {
                break; // Достаточно кнопок
            }
        }

        if (historiesWithCreds.size() > MAX_BUTTONS_TO_SHOW) {
            sb.append("\n_More schemas available\\. Use `/list_schemas` to see all IDs and enter an ID manually if not listed above\\._");
        }
        if (buttonCount == 0 && historiesWithCreds.size() > 0) { // Если были истории с кредами, но не поместились в кнопки
            sb.append("\n_Use `/list_schemas` to see all IDs and enter an ID manually\\._");
        }
        sb.append("\nOr type the ID directly if you know it\\.");

        SendMessage listMessage = SendMessage.builder()
                .chatId(chatId)
                .text(sb.toString())
                .parseMode("MarkdownV2")
                .replyMarkup(keyboardMarkupBuilder.build())
                .build();
        tryExecute(listMessage);
    }

    private void handleScheduleCreationInput(long chatId, long userId, AppUser appUser, String text, UserState currentState) {
        ScheduleCreationState state = scheduleCreationStates.get(userId);
        if (state == null) {
            sendMessage(chatId, "Error: Schedule creation process not found\\. Please start again with `/schedule_query`\\.");
            userStates.remove(userId);
            return;
        }

        switch (currentState) {
            case WAITING_FOR_SCHEDULE_NAME:
                handleScheduleNameInput(chatId, userId, appUser, text);
                break;
            case WAITING_FOR_SCHEDULE_HISTORY_ID:
                handleScheduleHistoryIdInput(chatId, userId, appUser, text);
                break;
            case WAITING_FOR_SCHEDULE_SQL:
                handleScheduleSqlInput(chatId, userId, appUser, text);
                break;
            case WAITING_FOR_SCHEDULE_CRON:
                handleScheduleCronInput(chatId, userId, appUser, text);
                break;
            case WAITING_FOR_SCHEDULE_CHAT_ID:
                handleScheduleChatIdInput(chatId, userId, appUser, text);
                break;
            case WAITING_FOR_SCHEDULE_OUTPUT_FORMAT:
                // Этот шаг лучше делать через кнопки, но если текстом:
                handleScheduleOutputFormatInput(chatId, userId, appUser, text);
                break;
            default:
                log.warn("Unexpected state {} in handleScheduleCreationInput", currentState);
                userStates.remove(userId);
                scheduleCreationStates.remove(userId);
                sendMessage(chatId, "An unexpected error occurred in scheduling\\. Please start over\\.");
        }
    }

    private void handleScheduleSqlInput(long chatId, long userId, AppUser appUser, String sql) {
        ScheduleCreationState state = scheduleCreationStates.get(userId);
        if (state == null) { return; }
        if (sql.trim().isEmpty() || !sql.trim().toLowerCase().startsWith("select")) { // Простая проверка
            sendMessage(chatId, "❌ Invalid SQL query\\. It should start with `SELECT` and not be empty\\. Please try again:");
            return;
        }
        state.setSqlQuery(sql.trim());
        userStates.put(userId, UserState.WAITING_FOR_SCHEDULE_CRON);
        sendMessage(chatId, "✅ SQL query set\\.\nNow, please enter the **CRON expression** for the schedule \\(e\\.g\\., `0 0 * * *` for daily at midnight, `0 12 * * MON-FRI` for noon on weekdays\\)\\.\nFor help with CRON, you can use an online generator like [crontab\\.guru](https://crontab.guru/)\\.");
    }

    private void handleScheduleCronInput(long chatId, long userId, AppUser appUser, String cronExpressionStr) {
        ScheduleCreationState state = scheduleCreationStates.get(userId);
        if (state == null) { return; }
        try {
            CronExpression.parse(cronExpressionStr.trim()); // Валидация CRON
            state.setCronExpression(cronExpressionStr.trim());
            userStates.put(userId, UserState.WAITING_FOR_SCHEDULE_CHAT_ID);
            sendMessage(chatId, "✅ CRON expression set to: `" + escapeMarkdownV2(cronExpressionStr.trim()) + "`\\.\nNow, please enter the **Chat ID** where results should be sent\\. Type `this` to use the current chat\\.");
        } catch (IllegalArgumentException e) {
            sendMessage(chatId, "❌ Invalid CRON expression: " + escapeMarkdownV2(e.getMessage()) + "\\. Please try again:");
        }
    }

    private void handleScheduleChatIdInput(long chatId, long userId, AppUser appUser, String chatIdStr) {
        ScheduleCreationState state = scheduleCreationStates.get(userId);
        if (state == null) {
            sendMessage(chatId, "Error: Schedule creation session expired or not found\\. Please start over with `/schedule_query`\\.");
            userStates.remove(userId); // Очищаем состояние
            return;
        }
        long targetChatId;
        if ("this".equalsIgnoreCase(chatIdStr.trim())) {
            targetChatId = chatId;
        } else {
            try {
                targetChatId = Long.parseLong(chatIdStr.trim());
                // Дополнительная проверка: может ли бот писать в этот чат?
                // Это сложно проверить заранее без попытки отправки.
                // Пока оставим так, но в будущем можно добавить обработку ошибки при отправке уведомления.
            } catch (NumberFormatException e) {
                sendMessage(chatId, "❌ Invalid Chat ID format\\. Please enter a numeric ID or `this`\\.");
                return; // Остаемся в том же состоянии, чтобы пользователь мог исправить
            }
        }
        state.setChatIdToNotify(targetChatId);
        userStates.put(userId, UserState.WAITING_FOR_SCHEDULE_OUTPUT_FORMAT);

        var keyboardBuilder = InlineKeyboardMarkup.builder() // Используем Builder здесь
                .keyboardRow(List.of(InlineKeyboardButton.builder().text("Text in Chat").callbackData("sched_format:text").build()))
                .keyboardRow(List.of(InlineKeyboardButton.builder().text("TXT File").callbackData("sched_format:txt").build()))
                .keyboardRow(List.of(InlineKeyboardButton.builder().text("CSV File").callbackData("sched_format:csv").build()));

        // Создаем объект SendMessage для отправки с клавиатурой
        SendMessage messageWithKeyboard = SendMessage.builder()
                .chatId(chatId)
                .text("✅ Chat ID for notifications set to: `" + targetChatId + "`\\.\nFinally, choose the **output format** for the results:")
                .parseMode("MarkdownV2")
                .replyMarkup(keyboardBuilder.build())
                .build();
        tryExecute(messageWithKeyboard); // Используем tryExecute для отправки
    }

    private void tryExecute(SendMessage message) {
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send message with keyboard to chat ID {}: {}", message.getChatId(), e.getMessage());
            // Попытка отправить без клавиатуры, если проблема была в ней
            message.setReplyMarkup(null);
            try {
                execute(message);
                log.info("Successfully sent message as plain text fallback after keyboard error.");
            } catch (TelegramApiException ex) {
                log.error("Failed to send plain text message either to chat ID {}: {}", message.getChatId(), ex.getMessage());
            }
        }
    }

    private void handleScheduleOutputFormatInput(long chatId, long userId, AppUser appUser, String format) {
        // Этот метод будет вызываться, если пользователь ввел формат текстом,
        // но лучше обрабатывать это через CallbackQuery от кнопок.
        ScheduleCreationState state = scheduleCreationStates.get(userId);
        if (state == null) { return; }
        format = format.trim().toLowerCase();
        if (!List.of("text", "txt", "csv").contains(format)) {
            sendMessage(chatId, "❌ Invalid format\\. Please choose from `text`, `txt`, or `csv` or use the buttons\\.");
            // Оставляем в том же состоянии, чтобы пользователь мог выбрать кнопкой
            return;
        }
        state.setOutputFormat(format);
        saveScheduledQuery(chatId, userId, appUser);
    }


    private void handleScheduleHistoryIdInput(long chatId, long userId, AppUser appUser, String historyIdStr) {
        ScheduleCreationState state = scheduleCreationStates.get(userId);
        if (state == null) { /* ... */ return; }
        try {
            long historyId = Long.parseLong(historyIdStr.trim());
            Optional<AnalysisHistory> historyOpt = analysisHistoryRepository.findByIdAndAppUser(historyId, appUser); // Нужен такой метод в репо
            if (historyOpt.isEmpty()) {
                sendMessage(chatId, "❌ Invalid Analysis History ID or it does not belong to you\\. Please try again or use `/list_schemas` to find the correct ID\\.");
                return; // Остаемся в том же состоянии
            }
            if (!historyOpt.get().hasCredentials()) {
                sendMessage(chatId, "❌ The selected analysis history \\(ID: " + historyId + "\\) does not have database credentials set up\\. Please use `/set_db_credentials` for the repository `" + escapeMarkdownV2(historyOpt.get().getRepositoryUrl()) + "` first, or choose a different history\\.");
                return; // Остаемся в том же состоянии
            }
            state.setAnalysisHistoryId(historyId);
            state.setAnalysisHistory(historyOpt.get()); // Сохраняем для быстрого доступа
            userStates.put(userId, UserState.WAITING_FOR_SCHEDULE_SQL);
            sendMessage(chatId, "✅ Analysis History set to ID: " + historyId + " \\(`" + escapeMarkdownV2(historyOpt.get().getRepositoryUrl()) + "`\\)\\.\nNow, please enter the **SQL query** to be scheduled:");
        } catch (NumberFormatException e) {
            sendMessage(chatId, "❌ Invalid ID format\\. Please enter a numeric ID\\.");
        }
    }

    // ... Добавьте обработчики для WAITING_FOR_SCHEDULE_SQL, WAITING_FOR_SCHEDULE_CRON, и т.д.
    // Последний шаг (например, WAITING_FOR_SCHEDULE_OUTPUT_FORMAT) будет вызывать saveScheduledQuery(...)

    private void saveScheduledQuery(long chatId, long userId, AppUser appUser) {
        ScheduleCreationState state = scheduleCreationStates.get(userId);
        if (state == null || state.getAnalysisHistoryId() == null || state.getSqlQuery() == null || state.getCronExpression() == null || state.getChatIdToNotify() == null || state.getOutputFormat() == null) {
            sendMessage(chatId, "❌ Something went wrong, some information is missing\\. Please start over with `/schedule_query`\\.");
            userStates.remove(userId); scheduleCreationStates.remove(userId);
            return;
        }

        try {
            CronExpression cron = CronExpression.parse(state.getCronExpression());
            LocalDateTime nextExecution = cron.next(LocalDateTime.now());

            ScheduledQuery newScheduledQuery = new ScheduledQuery(
                    appUser,
                    state.getAnalysisHistory(), // Используем сохраненный объект
                    state.getSqlQuery(),
                    state.getCronExpression(),
                    state.getChatIdToNotify(),
                    state.getOutputFormat(),
                    state.getName()
            );
            newScheduledQuery.setNextExecutionAt(nextExecution);
            // String userTimeZone = getTimeZoneFromUser(appUser); // Метод для получения часового пояса (если реализовано)
            // newScheduledQuery.setTimezoneId(userTimeZone);

            scheduledQueryRepository.save(newScheduledQuery);
            sendMessage(chatId, "✅ Scheduled query '" + escapeMarkdownV2(state.getName()) + "' created successfully\\! Next execution: `" + escapeMarkdownV2(nextExecution.toString()) + "`");
        } catch (IllegalArgumentException e) {
            sendMessage(chatId, "❌ Invalid CRON expression: `" + escapeMarkdownV2(state.getCronExpression()) + "`\\. " + escapeMarkdownV2(e.getMessage()) + "\\. Please try again\\.");
            userStates.put(userId, UserState.WAITING_FOR_SCHEDULE_CRON); // Возвращаем на шаг ввода CRON
            sendMessage(chatId, "Please re\\-enter the CRON expression:");
            return; // Не очищаем состояние, чтобы пользователь мог исправить CRON
        } catch (Exception e) {
            log.error("Error saving scheduled query for user {}", userId, e);
            sendMessage(chatId, "❌ An error occurred while saving the scheduled query\\. Please try again\\.");
        }
        userStates.remove(userId);
        scheduleCreationStates.remove(userId);
    }


    private void handleListScheduledQueries(long chatId, AppUser appUser) {
        List<ScheduledQuery> schedules = scheduledQueryRepository.findByAppUserOrderByCreatedAtDesc(appUser);
        if (schedules.isEmpty()) {
            sendMessage(chatId, "You have no scheduled queries\\. Use `/schedule_query` to create one\\.");
            return;
        }
        StringBuilder sb = new StringBuilder("Your Scheduled Queries:\n");
        for (ScheduledQuery sq : schedules) {
            sb.append(String.format("ID: `%d` Name: `%s` %s\n  Repo: `%s`\n  CRON: `%s`\n  Next Run: `%s`\n  Output: %s\n\\-\\--\n",
                    sq.getId(),
                    escapeMarkdownV2(sq.getName() != null ? sq.getName() : "N/A"),
                    sq.isEnabled() ? "✅" : "⏸️",
                    escapeMarkdownV2(sq.getAnalysisHistory().getRepositoryUrl()),
                    escapeMarkdownV2(sq.getCronExpression()),
                    escapeMarkdownV2(sq.getNextExecutionAt() != null ? sq.getNextExecutionAt().toString() : "N/A"),
                    escapeMarkdownV2(sq.getOutputFormat())
            ));
            // TODO: Добавить кнопки для управления каждым расписанием
        }
        sendMessage(chatId, sb.toString());
    }
}