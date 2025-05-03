package com.example.cognitivequery.bot;

import com.example.cognitivequery.model.AnalysisHistory;
import com.example.cognitivequery.model.AppUser;
import com.example.cognitivequery.repository.AnalysisHistoryRepository;
import com.example.cognitivequery.repository.UserRepository;
import com.example.cognitivequery.service.llm.GeminiService;
import com.example.cognitivequery.service.projectextractor.GitInfoService;
import com.example.cognitivequery.service.projectextractor.ProjectAnalyzerService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Slf4j
public class CognitiveQueryTelegramBot extends TelegramLongPollingBot {

    private final String botUsername;
    private final UserRepository userRepository;
    private final ProjectAnalyzerService projectAnalyzerService;
    private final GitInfoService gitInfoService;
    private final GeminiService geminiService;
    private final AnalysisHistoryRepository analysisHistoryRepository;

    private final Map<Long, UserState> userStates = new ConcurrentHashMap<>();
    private final Map<Long, String> userQueryContextRepoUrl = new ConcurrentHashMap<>();

    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile(
            "^(https?://)?(www\\.)?github\\.com/[\\w.-]+/[\\w.-]+(/)?(?:\\.git)?/?$", Pattern.CASE_INSENSITIVE);
    // Pattern for MarkdownV2 escaping - REMOVED hyphen (-)
    private static final Pattern ESCAPE_PATTERN = Pattern.compile("([_*~`>\\[\\]()#\\+=|{}.!])"); // Removed hyphen

    public CognitiveQueryTelegramBot(
            @Value("${telegram.bot.token}") String botToken,
            @Value("${telegram.bot.username}") String botUsername,
            @Value("${backend.api.base-url}") String backendApiBaseUrl,
            UserRepository userRepository,
            ProjectAnalyzerService projectAnalyzerService,
            GitInfoService gitInfoService,
            GeminiService geminiService,
            AnalysisHistoryRepository analysisHistoryRepository
    ) {
        super(botToken);
        this.botUsername = botUsername;
        this.userRepository = userRepository;
        this.projectAnalyzerService = projectAnalyzerService;
        this.gitInfoService = gitInfoService;
        this.geminiService = geminiService;
        this.analysisHistoryRepository = analysisHistoryRepository;
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
        commands.add(new BotCommand("start", "Start interaction"));
        commands.add(new BotCommand("connect_github", "Link GitHub account"));
        commands.add(new BotCommand("analyze_repo", "Analyze repository schema"));
        commands.add(new BotCommand("query", "Ask question about data (uses context)"));
        commands.add(new BotCommand("list_schemas", "List analyzed repositories"));
        commands.add(new BotCommand("use_schema", "Set context for /query by URL"));
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

                if (currentState == UserState.WAITING_FOR_REPO_URL) {
                    if (!messageText.startsWith("/")) {
                        log.debug("Handling message as repo URL input.");
                        handleRepoUrlInput(chatId, userId, appUser, messageText);
                        processed = true;
                    } else {
                        log.warn("Received command '{}' while waiting for repo URL. Resetting state.", messageText);
                        userStates.remove(userId);
                    }
                } else if (currentState == UserState.WAITING_FOR_LLM_QUERY) {
                    if (!messageText.startsWith("/")) {
                        log.debug("Handling message as query text input.");
                        handleQueryInput(chatId, userId, appUser, messageText);
                        processed = true;
                    } else {
                        log.warn("Received command '{}' while waiting for query text. Resetting state.", messageText);
                        userStates.remove(userId);
                    }
                }

                if (!processed && messageText.startsWith("/")) {
                    log.debug("Processing message as a command: {}", messageText);
                    if (messageText.toLowerCase().startsWith("/query ")) {
                        String queryText = messageText.substring(7).trim();
                        if (!queryText.isEmpty()) {
                            handleQueryCommand(chatId, appUser, queryText);
                        } else {
                            sendMessage(chatId, "Please provide your query after the `/query` command\\.\nExample: `/query show all tasks`");
                            userStates.put(userId, UserState.WAITING_FOR_LLM_QUERY);
                            sendMessage(chatId, "Alternatively, just type your query now\\.");
                        }
                        processed = true;
                    } else if (messageText.toLowerCase().startsWith("/use_schema ")) {
                        String repoUrl = messageText.substring(12).trim();
                        handleUseSchemaCommand(chatId, appUser, repoUrl);
                        processed = true;
                    } else {
                        handleCommand(chatId, userId, telegramIdStr, appUser, messageText, userFirstName);
                        processed = true;
                    }
                }

                if (!processed) {
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

    private void handleCommand(long chatId, long userId, String telegramIdStr, AppUser appUser, String command, String userFirstName) {
        if (!command.startsWith("/query") && !command.startsWith("/use_schema")) {
            userStates.put(userId, UserState.IDLE);
            userQueryContextRepoUrl.remove(userId);
        }
        if (appUser == null && (command.equals("/analyze_repo") || command.equals("/query") || command.equals("/list_schemas") || command.startsWith("/use_schema"))) {
            log.warn("Cannot execute command {} because user data is unavailable", command);
            sendMessage(chatId, "Cannot process command due to a temporary issue accessing user data\\.");
            return;
        }

        switch (command) {
            case "/start":
                sendMessage(chatId, "Hello, " + escapeMarkdownV2(userFirstName) + "\\! I'm CognitiveQuery bot\\.\n➡️ Use `/connect_github`\n➡️ Use `/analyze_repo`\n➡️ Use `/query <your question>`\n➡️ Use `/list_schemas` & `/use_schema <url>`");
                break;
            case "/connect_github":
                initiateGithubAuthFlow(chatId, telegramIdStr);
                break;
            case "/analyze_repo":
                startRepoAnalysisFlow(chatId, userId, appUser); // Pass userId
                break;
            case "/list_schemas":
                handleListSchemasCommand(chatId, appUser);
                break;
            case "/help":
                sendMessage(chatId, "Available commands:\n" +
                        "`/connect_github` \\- Link GitHub\n" +
                        "`/analyze_repo` \\- Analyze repository schema\n" +
                        "`/query <question>` \\- Ask about data \\(uses last/set repo\\)\n" +
                        "`/list_schemas` \\- List analyzed repositories\n" +
                        "`/use_schema <repo_url>` \\- Set context for `/query`\n" +
                        "`/help` \\- Show this message");
                break;
            default:
                if (!command.startsWith("/query") && !command.startsWith("/use_schema")) {
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
                .values().stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .sorted(Comparator.comparing(AnalysisHistory::getAnalyzedAt).reversed())
                .limit(15)
                .forEach(h -> {
                    String repoUrl = h.getRepositoryUrl();
                    String commit = h.getCommitHash().substring(0, 7);
                    String formattedDate = h.getAnalyzedAt().format(DateTimeFormatter.ISO_DATE);
                    String escapedRepoUrl = escapeMarkdownV2(repoUrl);
                    String escapedCommit = escapeMarkdownV2(commit);
                    String escapedDate = formattedDate.replace("-", "\\-");

                    sb.append(String.format("\\- `%s` \\(Analyzed: %s, Version: `%s`\\)\n",
                            escapedRepoUrl,
                            escapedDate,
                            escapedCommit));
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
            userQueryContextRepoUrl.put(appUser.getId(), repoUrl);
            sendMessage(chatId, "✅ Query context set to: `" + escapeMarkdownV2(repoUrl) +
                    "`\\.\nNow you can use `/query <your question>`\\.");
        } else {
            sendMessage(chatId, "❌ You haven't analyzed this repository yet: `" + escapeMarkdownV2(repoUrl) +
                    "`\\.\nPlease use `/analyze_repo` first\\.");
        }
    }


    private void initiateGithubAuthFlow(long chatId, String telegramId) { /* ... unchanged ... */ }

    private void startRepoAnalysisFlow(long chatId, long userId, AppUser appUser) { // Added userId parameter
        if (appUser.getGithubId() == null || appUser.getGithubId().isEmpty()) {
            log.warn("User attempted analysis without linked GitHub account.");
            sendMessage(chatId, "You need to connect your GitHub account first\\. Use `/connect_github`\\.");
            return;
        }
        log.info("Starting analysis flow for userId {}. Setting state to WAITING_FOR_REPO_URL.", userId);
        userStates.put(userId, UserState.WAITING_FOR_REPO_URL); // Use userId (long) as key
        sendMessage(chatId, "Please send me the full HTTPS URL of the public GitHub repository you want to analyze \\(e\\.g\\., `https://github.com/owner/repo`\\)\\.");
    }

    private void handleRepoUrlInput(long chatId, long userId, AppUser appUser, String repoUrl) {
        log.info("Handling repo URL input: '{}'", repoUrl);

        Matcher matcher = GITHUB_URL_PATTERN.matcher(repoUrl.trim());
        if (!matcher.matches()) {
            log.warn("Invalid GitHub URL format received: {}", repoUrl);
            sendMessage(chatId, "The URL doesn't look like a valid GitHub repository URL \\(e\\.g\\., `https://github.com/owner/repo`\\)\\. Please try `/analyze_repo` again\\.");
            userStates.remove(userId); // Reset state on invalid input
            return;
        }

        String validatedUrl = repoUrl.trim();
        final String userTelegramId = appUser.getTelegramId();

        sendMessage(chatId, "Checking repository status\\.\\.\\.");
        Optional<String> currentCommitHashOpt = gitInfoService.getRemoteHeadCommitHash(validatedUrl);
        String currentCommitHash = currentCommitHashOpt.orElse("UNKNOWN");

        Optional<AnalysisHistory> latestHistoryOpt = analysisHistoryRepository
                .findFirstByAppUserAndRepositoryUrlOrderByAnalyzedAtDesc(appUser, validatedUrl);

        boolean needsAnalysis = true;
        String reason = "";
        String existingSchemaPathStr = latestHistoryOpt.map(AnalysisHistory::getSchemaFilePath).orElse(null);
        String escapedValidatedUrl = escapeMarkdownV2(validatedUrl);

        if (latestHistoryOpt.isPresent() && !currentCommitHash.equals("UNKNOWN") && currentCommitHash.equals(latestHistoryOpt.get().getCommitHash())) {
            log.debug("Repository URL and Commit Hash match the last analysis.");
            if (existingSchemaPathStr != null && !existingSchemaPathStr.isBlank()) {
                try {
                    Path existingSchemaPath = Paths.get(existingSchemaPathStr);
                    if (Files.exists(existingSchemaPath) && Files.isRegularFile(existingSchemaPath)) {
                        log.info("Existing schema file found and is valid: {}", existingSchemaPath);
                        needsAnalysis = false;
                        userQueryContextRepoUrl.put(userId, validatedUrl);
                        String escapedPath = escapeMarkdownV2(existingSchemaPath.toString());
                        sendMessage(chatId, "✅ This repository version \\(" + escapedValidatedUrl + "\\) was already analyzed\\. Query context set\\.\nSchema is available at: `" + escapedPath + "`\n\\(Note: This path is on the server\\)\nYou can now ask questions using `/query <your question>`\\.");
                        userStates.remove(userId); // Reset state after successful cache hit
                    } else {
                        reason = "Previous analysis result file is missing.";
                        log.warn("Schema file path found in DB, but file does not exist: {}", existingSchemaPath);
                    }
                } catch (InvalidPathException e) {
                    reason = "Invalid path stored.";
                    log.error("Invalid schema file path stored in DB: {}", existingSchemaPathStr, e);
                }
            } else {
                reason = "Metadata exists, but result path is missing.";
                log.warn("Commit hash matches, but no schema path stored in DB");
            }
        } else if (latestHistoryOpt.isPresent()) {
            reason = "Repository has been updated.";
            log.info(reason);
        } else {
            reason = "This is a new repository URL.";
            log.info(reason);
        }


        if (needsAnalysis) {
            log.info("Proceeding with analysis. Reason: {}", reason.isEmpty() ? "Initial analysis or mismatch" : reason);
            performAnalysis(chatId, userId, appUser, validatedUrl, currentCommitHash);
            userStates.remove(userId); // Reset state after starting analysis
        }
    }

    private void performAnalysis(long chatId, long userId, AppUser appUser, String validatedUrl, String commitHashToSave) {
        String escapedUrl = escapeMarkdownV2(validatedUrl);
        String escapedHash = escapeMarkdownV2(commitHashToSave);
        String versionPart = commitHashToSave.equals("UNKNOWN") ? "" : " \\(version: `" + escapedHash.substring(0, Math.min(7, escapedHash.length())) + "`\\)";
        sendMessage(chatId, "⏳ Starting analysis for " + escapedUrl + versionPart + "\\.\\.\\. This may take a while\\.");

        Thread analysisThread = new Thread(() -> {
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
                log.info("Analysis thread complete. Schema IR file path: {}", resultSchemaPath);

                try {
                    AnalysisHistory newHistory = new AnalysisHistory(appUser, validatedUrl, commitHashToSave, resultSchemaPath.toString());
                    analysisHistoryRepository.save(newHistory);
                    log.info("Saved new analysis history record.");

                    List<AnalysisHistory> recordsToDelete = oldHistories.stream()
                            .filter(h -> !Objects.equals(h.getId(), newHistory.getId())) // Keep the new one if somehow in old list
                            .toList();
                    if (!recordsToDelete.isEmpty()) {
                        analysisHistoryRepository.deleteAll(recordsToDelete);
                        log.info("Deleted {} old history records for URL: {}", recordsToDelete.size(), validatedUrl);
                    }

                    if (oldSchemaPathToDelete != null && !oldSchemaPathToDelete.isBlank() && !oldSchemaPathToDelete.equals(resultSchemaPath.toString())) {
                        try {
                            Path oldPath = Paths.get(oldSchemaPathToDelete);
                            if (Files.deleteIfExists(oldPath)) {
                                log.info("Deleted previous schema file: {}", oldPath);
                            } else {
                                log.warn("Previous schema file not found for deletion: {}", oldPath);
                            }
                        } catch (Exception e) {
                            log.error("Failed to delete previous schema file: {}", oldSchemaPathToDelete, e);
                        }
                    }

                    userQueryContextRepoUrl.put(userId, validatedUrl);

                    String currentEscapedPath = escapeMarkdownV2(resultSchemaPath.toString());
                    sendMessage(chatId, "✅ Analysis successful\\! Schema saved\\.\nRepository: " + escapedUrl +
                            "\nQuery context automatically set to this repository\\.\n" +
                            "You can now ask questions using `/query <your question>`");

                } catch (Exception dbEx) {
                    log.error("Failed to save/cleanup analysis results in DB/FS after successful analysis", dbEx);
                    String currentEscapedPath = (resultSchemaPath != null) ? escapeMarkdownV2(resultSchemaPath.toString()) : "UNKNOWN";
                    sendMessage(chatId, "⚠️ Analysis was done \\(schema generated at `" + currentEscapedPath + "`\\), but I failed to save the results metadata or cleanup old files\\.");
                }

            } catch (Exception analysisEx) {
                log.error("Analysis failed for URL {}", validatedUrl, analysisEx);
                String reason = analysisEx.getMessage();
                if (analysisEx.getCause() != null) {
                    reason += " \\(Cause: " + analysisEx.getCause().getMessage() + "\\)";
                }
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
        String repoUrlForQuery = userQueryContextRepoUrl.get(appUser.getId());
        Optional<AnalysisHistory> targetHistoryOpt;
        String contextMessage;

        if (repoUrlForQuery != null) {
            log.info("Using query context for URL: {}", repoUrlForQuery);
            targetHistoryOpt = analysisHistoryRepository.findFirstByAppUserAndRepositoryUrlOrderByAnalyzedAtDesc(appUser, repoUrlForQuery);
            contextMessage = "based on the schema for `" + escapeMarkdownV2(repoUrlForQuery) + "`";
            if (targetHistoryOpt.isEmpty()) {
                sendMessage(chatId, "The repository you set for context \\(`" + escapeMarkdownV2(repoUrlForQuery) + "`\\) seems to have no analysis history\\. Please use `/analyze_repo` or `/list_schemas` and `/use_schema`\\.");
                userQueryContextRepoUrl.remove(appUser.getId());
                return;
            }
        } else {
            log.info("No query context set, using the latest analyzed repository.");
            targetHistoryOpt = analysisHistoryRepository.findFirstByAppUserOrderByAnalyzedAtDesc(appUser);
            if (targetHistoryOpt.isEmpty()) {
                sendMessage(chatId, "You haven't analyzed any repository yet\\. Please use `/analyze_repo` first\\.");
                return;
            }
            repoUrlForQuery = targetHistoryOpt.get().getRepositoryUrl();
            contextMessage = "based on the *latest* schema analyzed \\(`" + escapeMarkdownV2(repoUrlForQuery) + "`\\)";
            log.info("Defaulting query context to URL: {}", repoUrlForQuery);
        }

        AnalysisHistory targetHistory = targetHistoryOpt.get();
        String schemaPathStr = targetHistory.getSchemaFilePath();
        String commitHash = targetHistory.getCommitHash();
        String escapedRepoUrlForQuery = escapeMarkdownV2(repoUrlForQuery);

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

        Thread llmThread = new Thread(() -> {
            MDC.put("telegramId", appUser.getTelegramId());
            try {
                Optional<String> generatedSqlOpt = geminiService.generateSqlFromSchema(schemaJson, userQuery);
                if (generatedSqlOpt.isPresent()) {
                    String sql = generatedSqlOpt.get();
                    log.info("Generated SQL received.");
                    String escapedSql = sql.replace("\\", "\\\\").replace("`", "\\`");
                    sendMessage(chatId, "🤖 Generated SQL query:\n\n`" + escapedSql + "`\n\n" +
                            "*Disclaimer:* Review this query before execution\\.");
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
        llmThread.setName("LLMQueryThread-" + appUser.getId());
        llmThread.start();
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

    private enum UserState {IDLE, WAITING_FOR_REPO_URL, WAITING_FOR_LLM_QUERY}
}