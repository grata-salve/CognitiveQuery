package com.example.cognitivequery.bot.handler;

import com.example.cognitivequery.bot.CognitiveQueryTelegramBot;
import com.example.cognitivequery.bot.model.DbCredentialsInput;
import com.example.cognitivequery.bot.model.ScheduleCreationState;
import com.example.cognitivequery.bot.model.UserState;
import com.example.cognitivequery.bot.service.BotStateService;
import com.example.cognitivequery.model.AnalysisHistory;
import com.example.cognitivequery.model.AppUser;
import com.example.cognitivequery.model.ScheduledQuery;
import com.example.cognitivequery.repository.AnalysisHistoryRepository;
import com.example.cognitivequery.repository.ScheduledQueryRepository;
import com.example.cognitivequery.repository.UserRepository;
import com.example.cognitivequery.service.SchemaVisualizerService;
import com.example.cognitivequery.service.db.DynamicQueryExecutorService;
import com.example.cognitivequery.service.llm.GeminiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BotCommandHandler {

    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final BotStateService botStateService;
    private final WebClient webClient;
    private final GeminiService geminiService;
    private final ScheduledQueryRepository scheduledQueryRepository;
    private final SchemaVisualizerService schemaVisualizerService;
    private final DynamicQueryExecutorService queryExecutorService;
    private final UserRepository userRepository;

    private final String backendApiBaseUrl;


    public BotCommandHandler(
            AnalysisHistoryRepository analysisHistoryRepository,
            BotStateService botStateService,
            WebClient.Builder webClientBuilder,
            @Value("${backend.api.base-url}") String backendApiBaseUrl,
            GeminiService geminiService,
            ScheduledQueryRepository scheduledQueryRepository,
            DynamicQueryExecutorService queryExecutorService,
            SchemaVisualizerService schemaVisualizerService,
            UserRepository userRepository
    ) {
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.botStateService = botStateService;
        this.backendApiBaseUrl = backendApiBaseUrl;
        this.webClient = webClientBuilder.baseUrl(this.backendApiBaseUrl).build();
        this.geminiService = geminiService;
        this.scheduledQueryRepository = scheduledQueryRepository;
        this.queryExecutorService = queryExecutorService;
        this.schemaVisualizerService = schemaVisualizerService;
        this.userRepository = userRepository;
    }

    public void handle(Message message, AppUser appUser, String command, String commandArgs, String userFirstName,
                       TelegramMessageHelper messageHelper, ExecutorService taskExecutor) {
        long chatId = message.getChatId();
        long userId = appUser.getId();
        String telegramIdStr = appUser.getTelegramId();

        // Clear temporary input states (e.g., if we were in the middle of entering credentials), but do not clear the selected DB context (UserQueryContextHistoryId).
        botStateService.clearAllCommandInitiatedStates(userId);

        switch (command) {
            case "/start":
                // On start, reset all context, including the selected database.
                botStateService.clearUserQueryContextHistoryId(userId);
                handleStartCommand(chatId, userFirstName, messageHelper);
                break;
            case "/connect_github":
                initiateGithubAuthFlow(chatId, telegramIdStr, messageHelper);
                break;
            case "/analyze_repo":
                handleAnalyzeRepoCommand(chatId, userId, appUser, messageHelper);
                break;
            case "/query":
                handleQueryCommand(chatId, appUser, commandArgs, messageHelper, taskExecutor);
                break;
            case "/list_schemas":
                handleListSchemasCommand(chatId, appUser, messageHelper);
                break;
            case "/use_schema":
                handleUseSchemaCommand(chatId, appUser, commandArgs, messageHelper);
                break;
            case "/show_schema":
                handleShowSchemaCommand(chatId, appUser, messageHelper);
                break;
            case "/set_db_credentials":
                handleSetDbCredentialsCommand(chatId, userId, messageHelper);
                break;
            case "/schedule_query":
                handleScheduleQueryCommand(chatId, userId, appUser, messageHelper);
                break;
            case "/list_scheduled_queries":
                handleListScheduledQueriesCommand(chatId, appUser, messageHelper);
                break;
            case "/help":
                handleHelpCommand(chatId, messageHelper);
                break;
            case "/settings":
                handleSettingsCommand(chatId, appUser, messageHelper);
                break;
            default:
                messageHelper.sendMessage(chatId, "Sorry, I don't understand that command\\. Try `/help`\\.");
                break;
        }
    }

    private void handleStartCommand(long chatId, String userFirstName, TelegramMessageHelper messageHelper) {
        messageHelper.sendMessage(chatId, "Hello, " + messageHelper.escapeMarkdownV2(userFirstName) + "\\! I'm CognitiveQuery bot\\.\n" +
                                          "➡️ Use `/connect_github`\n" +
                                          "➡️ Use `/analyze_repo`\n" +
                                          "➡️ Use `/query <your question>`\n" +
                                          "➡️ Use `/list_schemas` & `/use_schema <url>`\n" +
                                          "➡️ Use `/set_db_credentials`\n" +
                                          "➡️ Use `/schedule_query`");
    }

    private void handleHelpCommand(long chatId, TelegramMessageHelper messageHelper) {
        messageHelper.sendMessage(chatId, "Available commands:\n" +
                                          "`/connect_github` \\- Link GitHub\n" +
                                          "`/analyze_repo` \\- Analyze repository schema\n" +
                                          "`/query <question>` \\- Ask about data \\(add `--csv` or `--txt` for file output\\)\n" +
                                          "`/list_schemas` \\- List analyzed repositories\n" +
                                          "`/use_schema <repo_url>` \\- Set context for `/query`\n" +
                                          "`/set_db_credentials` \\- Set DB credentials for a repo\n" +
                                          "`/schedule_query` \\- Create a new scheduled query\n" +
                                          "`/list_scheduled_queries` \\- List your scheduled queries\n" +
                                          "`/show_schema` \\- Visualize current DB schema\n" +
                                          "`/help` \\- Show this message");
    }

    private void handleSettingsCommand(long chatId, AppUser appUser, TelegramMessageHelper messageHelper) {
        String vizStatus = appUser.isVisualizationEnabled() ? "✅ ON" : "❌ OFF";
        String aiStatus = appUser.isAiInsightsEnabled() ? "✅ ON" : "❌ OFF";
        String modStatus = appUser.isDataModificationEnabled() ? "⚠️ ON" : "🔒 OFF";
        String sqlStatus = appUser.isShowSqlEnabled() ? "👁️ ON" : "❌ OFF";
        String dryStatus = appUser.isDryRunEnabled() ? "🚧 ON" : "❌ OFF";

        String text = String.format("""
                ⚙️ **User Settings**
                
                📊 **Visualization:** %s
                💡 **AI Insights:** %s
                ✏️ **Data Modification:** %s
                
                👁️ **Show SQL:** %s
                _(Show generated code in chat)_
                
                🚧 **Dry Run Mode:** %s
                _(Generate SQL ONLY, do not execute)_
                
                Tap to toggle:
                """, vizStatus, aiStatus, modStatus, sqlStatus, dryStatus);

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder().text("📊 Viz").callbackData("settings:toggle_viz").build(),
                        InlineKeyboardButton.builder().text("💡 AI").callbackData("settings:toggle_ai").build()
                ))
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder().text("👁️ Show SQL").callbackData("settings:toggle_show_sql").build(),
                        InlineKeyboardButton.builder().text("🚧 Dry Run").callbackData("settings:toggle_dry_run").build()
                ))
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder().text("✏️ Data Mod").callbackData("settings:toggle_mod").build()
                ))
                .build();

        SendMessage sm = SendMessage.builder().chatId(chatId).text(text).parseMode("Markdown").replyMarkup(markup).build();
        messageHelper.tryExecute(sm);
    }

    private void initiateGithubAuthFlow(long chatId, String telegramId, TelegramMessageHelper messageHelper) {
        log.info("Initiating GitHub auth for Telegram ID: {}", telegramId);
        messageHelper.sendMessage(chatId, "Requesting authorization URL from backend\\.\\.\\.");
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
                messageHelper.sendMessage(chatId, reply);
            } else {
                log.error("Failed to get GitHub authorization URL. Response: {}", response);
                messageHelper.sendMessage(chatId, "Sorry, I couldn't get the authorization link\\. Unexpected response from backend\\.");
            }
        } catch (Exception e) {
            log.error("Error calling backend API for GitHub auth", e);
            messageHelper.sendMessage(chatId, "An error occurred while contacting backend \\(" + messageHelper.escapeMarkdownV2(e.getMessage()) + "\\)\\.");
        }
    }

    private void handleAnalyzeRepoCommand(long chatId, long userId, AppUser appUser, TelegramMessageHelper messageHelper) {
        if (appUser.getGithubId() == null || appUser.getGithubId().isEmpty()) {
            messageHelper.sendMessage(chatId, "You need to connect your GitHub account first\\. Use `/connect_github`\\.");
            return;
        }
        botStateService.getOrCreateAnalysisInputState(userId);
        botStateService.setUserState(userId, UserState.WAITING_FOR_REPO_URL);
        messageHelper.sendMessage(chatId, "Please send me the full HTTPS URL of the public GitHub repository:");
    }

    private void handleListSchemasCommand(long chatId, AppUser appUser, TelegramMessageHelper messageHelper) {
        List<AnalysisHistory> history = analysisHistoryRepository.findByAppUserOrderByAnalyzedAtDesc(appUser);

        if (history.isEmpty()) {
            messageHelper.sendMessage(chatId, "📭 You haven't analyzed any repositories yet. Use `/analyze_repo`.");
            return;
        }

        // Group by URL to show only the latest analysis version for each repository.
        Map<String, AnalysisHistory> uniqueRepos = history.stream()
                .collect(Collectors.groupingBy(
                        AnalysisHistory::getRepositoryUrl,
                        Collectors.collectingAndThen(
                                Collectors.maxBy(Comparator.comparing(AnalysisHistory::getAnalyzedAt)),
                                Optional::get
                        )
                ));

        Long currentContextId = botStateService.getUserQueryContextHistoryId(appUser.getId());

        org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup.InlineKeyboardMarkupBuilder markupBuilder =
                org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup.builder();

        StringBuilder msgText = new StringBuilder("📂 **Select a Database to work with:**\n\n");

        for (AnalysisHistory h : uniqueRepos.values()) {
            String repoName = extractRepoName(h.getRepositoryUrl());
            boolean isActive = currentContextId != null && currentContextId.equals(h.getId());

            String icon = isActive ? "✅ " : "📁 ";
            String buttonText = icon + repoName;

            markupBuilder.keyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text(buttonText)
                            .callbackData("set_context:" + h.getId())
                            .build()
            ));
        }

        msgText.append("Tap a button below to switch context for `/query` and `/show_schema`.");

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(msgText.toString())
                .parseMode("Markdown")
                .replyMarkup(markupBuilder.build())
                .build();

        messageHelper.tryExecute(message);
    }

    private String extractRepoName(String url) {
        if (url == null) return "Unknown";

        String clean = url.replaceFirst("^https?://", "");

        clean = clean.replaceFirst("^github\\.com/", "");

        clean = clean.replaceFirst("\\.git$", "");

        return clean;
    }

    private void handleUseSchemaCommand(long chatId, AppUser appUser, String repoUrl, TelegramMessageHelper messageHelper) {
        if (repoUrl == null || repoUrl.isBlank()) {
            messageHelper.sendMessage(chatId, "Please provide the repository URL, e\\.g\\., `/use_schema https://github.com/owner/repo`");
            return;
        }
        Optional<AnalysisHistory> historyOpt = analysisHistoryRepository.findFirstByAppUserAndRepositoryUrlOrderByAnalyzedAtDesc(appUser, repoUrl.trim());

        if (historyOpt.isPresent()) {
            botStateService.setUserQueryContextHistoryId(appUser.getId(), historyOpt.get().getId());

            messageHelper.sendMessage(chatId, "✅ Query context set to: `" + messageHelper.escapeMarkdownV2(repoUrl) +
                                              "` \\(version: `" + messageHelper.escapeMarkdownV2(historyOpt.get().getCommitHash().substring(0, 7)) + "`\\)\\.\n" +
                                              "Now you can use `/query <your question>` and `/show_schema` for this database\\.");
        } else {
            messageHelper.sendMessage(chatId, "❌ You haven't analyzed this repository yet: `" + messageHelper.escapeMarkdownV2(repoUrl) +
                                              "`\\.\nPlease use `/analyze_repo` first\\.");
        }
    }

    private void handleSetDbCredentialsCommand(long chatId, long userId, TelegramMessageHelper messageHelper) {
        botStateService.setCredentialsInputState(userId, new DbCredentialsInput());
        botStateService.setUserState(userId, UserState.WAITING_FOR_REPO_URL_FOR_CREDS);
        messageHelper.sendMessage(chatId, "Which repository's DB credentials do you want to set or update\\?\nPlease enter the full URL:");
    }

    private void handleQueryCommand(long chatId, AppUser appUser, String queryTextWithPotentialFlag,
                                    TelegramMessageHelper messageHelper, ExecutorService taskExecutor) {
        boolean isJustAFlag = (queryTextWithPotentialFlag.equalsIgnoreCase(CognitiveQueryTelegramBot.CSV_FLAG) && queryTextWithPotentialFlag.length() == CognitiveQueryTelegramBot.CSV_FLAG.length()) ||
                              (queryTextWithPotentialFlag.equalsIgnoreCase(CognitiveQueryTelegramBot.TXT_FLAG) && queryTextWithPotentialFlag.length() == CognitiveQueryTelegramBot.TXT_FLAG.length());

        if (!queryTextWithPotentialFlag.isEmpty() && !isJustAFlag) {
            processUserQuery(chatId, appUser, queryTextWithPotentialFlag, messageHelper, taskExecutor);
        } else {
            messageHelper.sendMessage(chatId, "Please provide your query after `/query`\\.\nExample: `/query show all tasks` or `/query show all tasks --csv`");
            botStateService.setUserState(appUser.getId(), UserState.WAITING_FOR_LLM_QUERY);
            messageHelper.sendMessage(chatId, "Alternatively, just type your query now\\.");
        }
    }

    public void processUserQuery(long chatId, AppUser appUser, String userQuery,
                                 TelegramMessageHelper messageHelper, ExecutorService taskExecutor) {
        String outputFormat = "text";
        String queryForLlm = userQuery.trim();

        // 1. Process format flags
        if (queryForLlm.toLowerCase().endsWith(" " + CognitiveQueryTelegramBot.CSV_FLAG)) {
            outputFormat = "csv";
            queryForLlm = queryForLlm.substring(0, queryForLlm.length() - (CognitiveQueryTelegramBot.CSV_FLAG.length() + 1)).trim();
        } else if (queryForLlm.toLowerCase().endsWith(" " + CognitiveQueryTelegramBot.TXT_FLAG)) {
            outputFormat = "txt";
            queryForLlm = queryForLlm.substring(0, queryForLlm.length() - (CognitiveQueryTelegramBot.TXT_FLAG.length() + 1)).trim();
        } else if (queryForLlm.toLowerCase().endsWith(" " + CognitiveQueryTelegramBot.EXCEL_FLAG)) {
            outputFormat = "excel";
            queryForLlm = queryForLlm.substring(0, queryForLlm.length() - (CognitiveQueryTelegramBot.EXCEL_FLAG.length() + 1)).trim();
        }

        if (queryForLlm.isEmpty()) {
            messageHelper.sendPlainTextMessage(chatId, "Query text cannot be empty.");
            return;
        }

        // 2. Determine DB context
        Long targetHistoryId = botStateService.getUserQueryContextHistoryId(appUser.getId());
        Optional<AnalysisHistory> targetHistoryOpt;
        String contextMessage;

        if (targetHistoryId != null) {
            targetHistoryOpt = analysisHistoryRepository.findById(targetHistoryId);
            if (targetHistoryOpt.isEmpty() || !Objects.equals(targetHistoryOpt.get().getAppUser().getId(), appUser.getId())) {
                messageHelper.sendPlainTextMessage(chatId, "Context error. Please select a schema via /list_schemas.");
                return;
            }
            contextMessage = "based on `" + messageHelper.escapeMarkdownV2(targetHistoryOpt.get().getRepositoryUrl()) + "`";
        } else {
            targetHistoryOpt = analysisHistoryRepository.findFirstByAppUserOrderByAnalyzedAtDesc(appUser);
            if (targetHistoryOpt.isEmpty()) {
                messageHelper.sendPlainTextMessage(chatId, "No analyzed repositories found. Use /analyze_repo first.");
                return;
            }
            contextMessage = "based on the *latest* schema";
            targetHistoryId = targetHistoryOpt.get().getId();
        }

        AnalysisHistory targetHistory = targetHistoryOpt.get();
        if (!targetHistory.hasCredentials()) {
            messageHelper.sendPlainTextMessage(chatId, "❌ DB credentials missing for this schema. Use /set_db_credentials.");
            return;
        }

        // 3. Read schema file
        String schemaPathStr = targetHistory.getSchemaFilePath();
        String schemaJson;
        try {
            schemaJson = Files.readString(Paths.get(schemaPathStr), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Error accessing schema file", e);
            messageHelper.sendPlainTextMessage(chatId, "❌ Error accessing schema file.");
            return;
        }

        messageHelper.sendMessage(chatId, "🧠 Analyzing " + contextMessage + " and executing query\\.\\.\\.");

        final Long finalTargetHistoryId = targetHistoryId;
        final long currentUserId = appUser.getId();
        final String finalOutputFormat = outputFormat;
        final String finalQueryForLlm = queryForLlm;

        // 4. Asynchronous execution
        taskExecutor.submit(() -> {
            org.slf4j.MDC.put("telegramId", appUser.getTelegramId());
            try {
                // Find conversational context
                String previousSql = null;
                String storedHistoryData = botStateService.getLastGeneratedSql(currentUserId);

                if (storedHistoryData != null && storedHistoryData.startsWith(finalTargetHistoryId + ":")) {
                    previousSql = storedHistoryData.substring(String.valueOf(finalTargetHistoryId).length() + 1);
                    log.info("Found conversational context: {}", previousSql);
                }

                // Generate SQL
                Optional<String> generatedSqlOpt = geminiService.generateSqlFromSchema(schemaJson, finalQueryForLlm, previousSql);

                if (generatedSqlOpt.isEmpty()) {
                    messageHelper.sendPlainTextMessage(chatId, "❌ I couldn't generate an SQL query. Try rephrasing.");
                    return;
                }

                String resultFromAi = generatedSqlOpt.get().trim();

                // Check for AI refusal to execute (NO_DATA)
                if (resultFromAi.startsWith("NO_DATA:")) {
                    String explanation = resultFromAi.substring("NO_DATA:".length()).trim();
                    messageHelper.sendPlainTextMessage(chatId, "🤔 " + explanation);
                    return;
                }

                String sqlToExecute = resultFromAi;

                // --- SETTINGS LOGIC (Show SQL / Dry Run) ---
                if (appUser.isDryRunEnabled()) {
                    messageHelper.sendPlainTextMessage(chatId, "🚧 **Dry Run Mode (SQL Only):**\n\n" + sqlToExecute);
                    botStateService.setLastGeneratedSql(currentUserId, finalTargetHistoryId + ":" + sqlToExecute);
                    return; // Stop execution
                }

                if (appUser.isShowSqlEnabled()) {
                    messageHelper.sendPlainTextMessage(chatId, "📝 **Generated SQL:**\n\n" + sqlToExecute);
                }
                // ---------------------------------------------

                boolean success = false;
                String lastError = "";

                // Attempt loop (Self-healing)
                for (int attempt = 1; attempt <= 2; attempt++) {
                    log.info("Executing SQL (Attempt {}): {}", attempt, sqlToExecute);

                    var result = queryExecutorService.executeQuery(
                            targetHistory.getDbHost(), targetHistory.getDbPort(), targetHistory.getDbName(),
                            targetHistory.getDbUser(), targetHistory.getDbPasswordEncrypted(),
                            sqlToExecute,
                            appUser.isDataModificationEnabled()
                    );

                    if (result.isSuccess()) {
                        success = true;
                        botStateService.setLastGeneratedSql(currentUserId, finalTargetHistoryId + ":" + sqlToExecute);

                        if (result.type() == DynamicQueryExecutorService.QueryType.SELECT) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> data = (List<Map<String, Object>>) result.data();

                            // Output results
                            if ("csv".equals(finalOutputFormat)) {
                                messageHelper.sendSelectResultAsCsvFile(chatId, data, targetHistory.getRepositoryUrl());
                            } else if ("txt".equals(finalOutputFormat)) {
                                messageHelper.sendSelectResultAsTxtFile(chatId, data, targetHistory.getRepositoryUrl());
                            } else if ("excel".equals(finalOutputFormat)) {
                                messageHelper.sendSelectResultAsExcelFile(chatId, data, targetHistory.getRepositoryUrl());
                            } else {
                                messageHelper.sendSelectResultAsTextInChat(chatId, data, targetHistory.getRepositoryUrl(), appUser.isVisualizationEnabled());
                            }

                            // AI Insights
                            if ("text".equals(finalOutputFormat) && !data.isEmpty() && data.size() <= 50 && appUser.isAiInsightsEnabled()) {
                                try {
                                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                                    mapper.findAndRegisterModules();
                                    String jsonData = mapper.writeValueAsString(data);

                                    Optional<String> insightOpt = geminiService.analyzeQueryResult(finalQueryForLlm, sqlToExecute, jsonData);

                                    if (insightOpt.isPresent()) {
                                        messageHelper.sendPlainTextMessage(chatId, "💡 AI Insight:\n" + insightOpt.get());
                                    }
                                } catch (Exception ex) {
                                    log.error("Failed to generate AI insight", ex);
                                }
                            }

                        } else {
                            messageHelper.sendPlainTextMessage(chatId, "✅ Query executed. Rows affected: " + result.data());
                        }

                        if (attempt > 1) {
                            messageHelper.sendPlainTextMessage(chatId, "🔧 Note: I automatically fixed an SQL error in the initial query.");
                        }
                        break; // Success

                    } else {
                        // Error -> attempt to fix
                        lastError = result.errorMessage();
                        log.warn("SQL execution failed on attempt {}: {}", attempt, lastError);

                        if (attempt == 1) {
                            messageHelper.sendPlainTextMessage(chatId, "⚠️ SQL error detected. Attempting to self-correct...");

                            Optional<String> fixedSqlOpt = geminiService.fixSql(schemaJson, sqlToExecute, lastError);

                            if (fixedSqlOpt.isPresent()) {
                                String res = fixedSqlOpt.get().trim();
                                if ("ABORT_EXPLAIN".equals(res)) {
                                    log.info("AI decided to abort self-correction.");
                                    break;
                                }
                                sqlToExecute = res;
                            } else {
                                break;
                            }
                        }
                    }
                }

                if (!success) {
                    messageHelper.sendPlainTextMessage(chatId, "❌ Query failed even after self-correction. Asking AI to explain why...");
                    Optional<String> explanationOpt = geminiService.explainError(schemaJson, sqlToExecute, lastError);

                    if (explanationOpt.isPresent()) {
                        messageHelper.sendPlainTextMessage(chatId, "💡 AI Explanation:\n" + explanationOpt.get());
                    } else {
                        messageHelper.sendPlainTextMessage(chatId, "❌ Technical Error:\n" + lastError);
                    }
                }

            } catch (Exception e) {
                log.error("Error during query execution flow", e);
                messageHelper.sendPlainTextMessage(chatId, "An unexpected error occurred: " + e.getMessage());
            } finally {
                org.slf4j.MDC.remove("telegramId");
            }
        });
    }

    private void handleScheduleQueryCommand(long chatId, long userId, AppUser appUser, TelegramMessageHelper messageHelper) {
        botStateService.setScheduleCreationState(userId, new ScheduleCreationState());
        botStateService.setUserState(userId, UserState.WAITING_FOR_SCHEDULE_NAME);
        messageHelper.sendMessage(chatId, "Let's create a new scheduled query\\! \nFirst, please enter a descriptive **name** for this schedule \\(e\\.g\\., `Daily User Report`\\):");
    }

    private void handleListScheduledQueriesCommand(long chatId, AppUser appUser, TelegramMessageHelper messageHelper) {
        List<ScheduledQuery> schedules = scheduledQueryRepository.findByAppUserWithHistoryOrderByCreatedAtDesc(appUser);
        if (schedules.isEmpty()) {
            messageHelper.sendMessage(chatId, "You have no scheduled queries\\. Use `/schedule_query` to create one\\.");
            return;
        }

        messageHelper.sendMessage(chatId, "Your Scheduled Queries \\(tap to manage\\):");

        for (ScheduledQuery sq : schedules) {
            String repoUrl = sq.getAnalysisHistory() != null ? sq.getAnalysisHistory().getRepositoryUrl() : "N/A (History Missing!)";
            String scheduleStatus = sq.isEnabled() ? "✅ Active" : "⏸️ Paused";
            String alertInfo = sq.getAlertCondition() != null && !sq.getAlertCondition().isBlank()
                    ? "\n*Alert:* `" + messageHelper.escapeMarkdownV2(sq.getAlertCondition()) + "`"
                    : "";

            String text = String.format(
                    "*Name:* `%s` \\(%s\\)\n" +
                    "*ID:* `%d`\n" +
                    "*Repo:* `%s`\n" +
                    "*CRON:* `%s`\n" +
                    "*Next Run:* `%s`\n" +
                    "*Output:* %s%s",
                    messageHelper.escapeMarkdownV2(sq.getName() != null ? sq.getName() : "N/A"),
                    scheduleStatus,
                    sq.getId(),
                    messageHelper.escapeMarkdownV2(repoUrl),
                    messageHelper.escapeMarkdownV2(sq.getCronExpression()),
                    messageHelper.escapeMarkdownV2(sq.getNextExecutionAt() != null ? sq.getNextExecutionAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : "N/A"),
                    messageHelper.escapeMarkdownV2(sq.getOutputFormat()),
                    alertInfo
            );

            List<InlineKeyboardButton> rowButtons = new ArrayList<>();
            if (sq.isEnabled()) {
                rowButtons.add(InlineKeyboardButton.builder()
                        .text("⏸️ Pause")
                        .callbackData("pause_sched:" + sq.getId())
                        .build());
            } else {
                rowButtons.add(InlineKeyboardButton.builder()
                        .text("▶️ Resume")
                        .callbackData("resume_sched:" + sq.getId())
                        .build());
            }
            rowButtons.add(InlineKeyboardButton.builder()
                    .text("❌ Delete")
                    .callbackData("delete_sched:" + sq.getId())
                    .build());
            // Potentially "✏️ Edit" button could be added here in the future


            InlineKeyboardMarkup keyboardMarkup = InlineKeyboardMarkup.builder()
                    .keyboardRow(rowButtons)
                    .build();

            SendMessage scheduleMessage = SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("MarkdownV2")
                    .replyMarkup(keyboardMarkup)
                    .build();
            messageHelper.tryExecute(scheduleMessage);
        }
        messageHelper.sendMessage(chatId, "_Use `/schedule_query` to create a new one\\._");
    }

    private void handleShowSchemaCommand(long chatId, AppUser appUser, TelegramMessageHelper messageHelper) {
        Long contextHistoryId = botStateService.getUserQueryContextHistoryId(appUser.getId());
        Optional<AnalysisHistory> historyOpt = Optional.empty();

        if (contextHistoryId != null) {
            historyOpt = analysisHistoryRepository.findById(contextHistoryId);
        }

        if (historyOpt.isEmpty()) {
            historyOpt = analysisHistoryRepository.findFirstByAppUserOrderByAnalyzedAtDesc(appUser);
        }

        if (historyOpt.isEmpty()) {
            messageHelper.sendPlainTextMessage(chatId, "❌ No analyzed repositories found. Please use /analyze_repo first.");
            return;
        }

        AnalysisHistory history = historyOpt.get();
        Path schemaPath = Paths.get(history.getSchemaFilePath());

        if (!Files.exists(schemaPath)) {
            messageHelper.sendPlainTextMessage(chatId, "❌ Schema file is missing on the server. Please re-analyze the repository.");
            return;
        }

        try {
            String schemaJson = Files.readString(schemaPath);
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            objectMapper.findAndRegisterModules();
            com.example.cognitivequery.model.ir.SchemaInfo schemaInfo = objectMapper.readValue(schemaJson, com.example.cognitivequery.model.ir.SchemaInfo.class);

            messageHelper.sendPlainTextMessage(chatId, "🎨 Generating schema visualization...");

            byte[] imageBytes = schemaVisualizerService.generateSchemaImage(schemaInfo);

            if (imageBytes != null && imageBytes.length > 0) {
                String caption = "🗂 Database Schema for " + history.getRepositoryUrl();
                messageHelper.sendImage(chatId, imageBytes, caption);
            } else {
                messageHelper.sendPlainTextMessage(chatId, "⚠️ Could not visualize schema (service returned empty data).");
            }

        } catch (Exception e) {
            log.error("Error visualizing schema for user {}", appUser.getId(), e);
            messageHelper.sendPlainTextMessage(chatId, "❌ Error generating schema diagram: " + e.getMessage());
        }
    }

    /**
     * Magic method that understands everything.
     */
    public void handleNaturalLanguage(long chatId, AppUser appUser, String text,
                                      TelegramMessageHelper messageHelper, ExecutorService taskExecutor) {

        // 1. Quick check for URL (to avoid time on AI classification)
        if (CognitiveQueryTelegramBot.GITHUB_URL_PATTERN.matcher(text.trim()).matches()) {
            // Initiate analysis flow
            botStateService.getOrCreateAnalysisInputState(appUser.getId());
            botStateService.setUserState(appUser.getId(), UserState.WAITING_FOR_REPO_URL);
            handleAnalyzeRepoCommand(chatId, appUser.getId(), appUser, messageHelper);
            return;
        }

        // 2. Ask AI for intent classification
        taskExecutor.submit(() -> {
            org.slf4j.MDC.put("telegramId", appUser.getTelegramId());
            try {
                String intent = geminiService.determineIntent(text);
                log.info("User input: '{}' -> Detected Intent: {}", text, intent);

                switch (intent) {
                    case "SHOW_SCHEMA":
                        handleShowSchemaCommand(chatId, appUser, messageHelper);
                        break;
                    case "SETTINGS":
                        String action = geminiService.extractSettingsAction(text);
                        applySettingAction(chatId, appUser, action, messageHelper);
                        break;
                    case "ANALYZE_REPO":
                        // If AI thinks it's a link, but the regex above failed
                        handleAnalyzeRepoCommand(chatId, appUser.getId(), appUser, messageHelper);
                        break;
                    case "QUERY":
                    default:
                        // Assume everything else is a query
                        processUserQuery(chatId, appUser, text, messageHelper, taskExecutor);
                        break;
                }
            } catch (Exception e) {
                log.error("Intent classification failed", e);
                // Fallback to regular query
                processUserQuery(chatId, appUser, text, messageHelper, taskExecutor);
            } finally {
                org.slf4j.MDC.remove("telegramId");
            }
        });
    }

    /**
     * Applies settings change from voice/text input.
     */
    private void applySettingAction(long chatId, AppUser appUser, String action, TelegramMessageHelper messageHelper) {
        if ("SHOW_MENU".equals(action)) {
            handleSettingsCommand(chatId, appUser, messageHelper);
            return;
        }

        String[] parts = action.split("=");
        if (parts.length != 2) {
            handleSettingsCommand(chatId, appUser, messageHelper);
            return;
        }

        String key = parts[0];
        boolean value = Boolean.parseBoolean(parts[1]);
        String responseText = "";

        switch (key) {
            case "VIZ":
                appUser.setVisualizationEnabled(value);
                break;
            case "AI":
                appUser.setAiInsightsEnabled(value);
                break;
            case "MOD":
                appUser.setDataModificationEnabled(value);
                break;
            case "SHOW_SQL":
                appUser.setShowSqlEnabled(value);
                break;
            case "DRY_RUN":
                appUser.setDryRunEnabled(value);
                break;
            default:
                handleSettingsCommand(chatId, appUser, messageHelper);
                return;
        }

        userRepository.save(appUser);

        String emoji = value ? "✅" : "🚫";
        messageHelper.sendMessage(chatId, String.format("%s Setting changed: **%s** is now **%s**", emoji, key, value ? "ON" : "OFF"));

        // Show updated menu immediately
        handleSettingsCommand(chatId, appUser, messageHelper);
    }
}