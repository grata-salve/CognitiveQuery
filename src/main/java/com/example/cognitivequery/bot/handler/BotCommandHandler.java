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
import com.example.cognitivequery.service.llm.GeminiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;


import java.io.IOException;
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

    private final String backendApiBaseUrl;


    public BotCommandHandler(
            AnalysisHistoryRepository analysisHistoryRepository,
            BotStateService botStateService,
            WebClient.Builder webClientBuilder,
            @Value("${backend.api.base-url}") String backendApiBaseUrl,
            GeminiService geminiService,
            ScheduledQueryRepository scheduledQueryRepository) {
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.botStateService = botStateService;
        this.backendApiBaseUrl = backendApiBaseUrl;
        this.webClient = webClientBuilder.baseUrl(this.backendApiBaseUrl).build();
        this.geminiService = geminiService;
        this.scheduledQueryRepository = scheduledQueryRepository;
    }

    public void handle(Message message, AppUser appUser, String command, String commandArgs, String userFirstName,
                       TelegramMessageHelper messageHelper, ExecutorService taskExecutor) {
        long chatId = message.getChatId();
        long userId = appUser.getId(); // Assuming AppUser has a getId() that corresponds to your internal user ID
        String telegramIdStr = appUser.getTelegramId();

        // Clear states for most commands, except those that initiate a flow or use existing state
        if (!Arrays.asList("/query", "/use_schema", "/set_db_credentials", "/schedule_query").contains(command)) {
            botStateService.clearAllCommandInitiatedStates(userId);
            botStateService.clearUserQueryContextHistoryId(userId); // Also clear context for general commands
        }

        switch (command) {
            case "/start":
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
                "`/help` \\- Show this message");
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
                String reply = "Please click the link below to authorize with GitHub:\n\n" + authUrl + // No MarkdownV2 escape for URL itself
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
        botStateService.getOrCreateAnalysisInputState(userId); // Ensure state object exists
        botStateService.setUserState(userId, UserState.WAITING_FOR_REPO_URL);
        messageHelper.sendMessage(chatId, "Please send me the full HTTPS URL of the public GitHub repository:");
    }

    private void handleListSchemasCommand(long chatId, AppUser appUser, TelegramMessageHelper messageHelper) {
        List<AnalysisHistory> history = analysisHistoryRepository.findByAppUserOrderByAnalyzedAtDesc(appUser);
        if (history.isEmpty()) {
            messageHelper.sendMessage(chatId, "You haven't analyzed any repositories yet\\. Use `/analyze_repo` first\\.");
            return;
        }
        StringBuilder sb = new StringBuilder("Analyzed repositories \\(latest first\\):\n");
        history.stream()
                .collect(Collectors.groupingBy(AnalysisHistory::getRepositoryUrl, Collectors.maxBy(Comparator.comparing(AnalysisHistory::getAnalyzedAt))))
                .values().stream().filter(Optional::isPresent).map(Optional::get)
                .sorted(Comparator.comparing(AnalysisHistory::getAnalyzedAt).reversed()).limit(15)
                .forEach(h -> {
                    String escapedRepoUrl = messageHelper.escapeMarkdownV2(h.getRepositoryUrl());
                    String escapedCommit = messageHelper.escapeMarkdownV2(h.getCommitHash().substring(0, 7));
                    String date = messageHelper.escapeMarkdownV2(h.getAnalyzedAt().format(DateTimeFormatter.ISO_DATE));
                    sb.append(String.format("\\- `%s` \\(Analyzed: %s, Version: `%s`\\)%s\n",
                            escapedRepoUrl, date, escapedCommit, h.hasCredentials() ? " \\(DB creds saved\\)" : ""));
                });
        sb.append("\nUse `/use_schema <repo_url>` to set the context for `/query`\\.\nUse `/set_db_credentials` to add/update DB details\\.");
        messageHelper.sendMessage(chatId, sb.toString());
    }

    private void handleUseSchemaCommand(long chatId, AppUser appUser, String repoUrl, TelegramMessageHelper messageHelper) {
        if (repoUrl == null || repoUrl.isBlank()) {
            messageHelper.sendMessage(chatId, "Please provide the repository URL, e\\.g\\., `/use_schema https://github.com/owner/repo`");
            return;
        }
        Optional<AnalysisHistory> historyOpt = analysisHistoryRepository.findFirstByAppUserAndRepositoryUrlOrderByAnalyzedAtDesc(appUser, repoUrl);
        if (historyOpt.isPresent()) {
            botStateService.setUserQueryContextHistoryId(appUser.getId(), historyOpt.get().getId());
            messageHelper.sendMessage(chatId, "✅ Query context set to: `" + messageHelper.escapeMarkdownV2(repoUrl) +
                    "` \\(version: `" + messageHelper.escapeMarkdownV2(historyOpt.get().getCommitHash().substring(0, 7)) + "`\\)\\.\n" +
                    "Now you can use `/query <your question>`\\.");
        } else {
            messageHelper.sendMessage(chatId, "❌ You haven't analyzed this repository yet: `" + messageHelper.escapeMarkdownV2(repoUrl) +
                    "`\\.\nPlease use `/analyze_repo` first\\.");
        }
    }

    private void handleSetDbCredentialsCommand(long chatId, long userId, TelegramMessageHelper messageHelper) {
        botStateService.setCredentialsInputState(userId, new DbCredentialsInput()); // Initialize if not present
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
        String originalQueryForLog = userQuery;

        if (queryForLlm.toLowerCase().endsWith(" " + CognitiveQueryTelegramBot.CSV_FLAG)) {
            outputFormat = "csv";
            queryForLlm = queryForLlm.substring(0, queryForLlm.length() - (CognitiveQueryTelegramBot.CSV_FLAG.length() + 1)).trim();
        } else if (queryForLlm.toLowerCase().endsWith(" " + CognitiveQueryTelegramBot.TXT_FLAG)) {
            outputFormat = "txt";
            queryForLlm = queryForLlm.substring(0, queryForLlm.length() - (CognitiveQueryTelegramBot.TXT_FLAG.length() + 1)).trim();
        }

        if (queryForLlm.isEmpty()) {
            messageHelper.sendMessage(chatId, "Query text cannot be empty, even after removing flags\\.");
            return;
        }
        log.info("Processing user query '{}'. Query for LLM: '{}'. Output format: {}", originalQueryForLog, queryForLlm, outputFormat);

        Long targetHistoryId = botStateService.getUserQueryContextHistoryId(appUser.getId());
        Optional<AnalysisHistory> targetHistoryOpt;
        String contextMessage;

        if (targetHistoryId != null) {
            targetHistoryOpt = analysisHistoryRepository.findById(targetHistoryId);
            if (targetHistoryOpt.isEmpty() || !Objects.equals(targetHistoryOpt.get().getAppUser().getId(), appUser.getId())) {
                messageHelper.sendMessage(chatId, "Context error or history not found for you\\. Please use `/list_schemas` and `/use_schema` again\\.");
                botStateService.clearUserQueryContextHistoryId(appUser.getId());
                return;
            }
            contextMessage = targetHistoryOpt.map(h -> "based on the schema for `" + messageHelper.escapeMarkdownV2(h.getRepositoryUrl()) + "`").orElse("based on a previously set context \\(not found\\?\\)");
        } else {
            targetHistoryOpt = analysisHistoryRepository.findFirstByAppUserOrderByAnalyzedAtDesc(appUser);
            if (targetHistoryOpt.isEmpty()) {
                messageHelper.sendMessage(chatId, "You haven't analyzed any repository yet\\. Please use `/analyze_repo` first\\.");
                return;
            }
            contextMessage = "based on the *latest* schema analyzed \\(`" + messageHelper.escapeMarkdownV2(targetHistoryOpt.get().getRepositoryUrl()) + "`\\)";
            targetHistoryId = targetHistoryOpt.get().getId();
            // Implicitly set context if none was set
            botStateService.setUserQueryContextHistoryId(appUser.getId(), targetHistoryId);
        }
        AnalysisHistory targetHistory = targetHistoryOpt.get(); // Should be present due to checks
        String schemaPathStr = targetHistory.getSchemaFilePath();
        String commitHash = targetHistory.getCommitHash();
        Path schemaPath;
        String schemaJson;
        try {
            schemaPath = Paths.get(schemaPathStr);
            if (!Files.exists(schemaPath) || !Files.isRegularFile(schemaPath)) {
                messageHelper.sendMessage(chatId, "Error: The schema file for `" + messageHelper.escapeMarkdownV2(targetHistory.getRepositoryUrl()) + "` is missing\\. Please run `/analyze_repo` again\\.");
                return;
            }
            schemaJson = Files.readString(schemaPath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Error accessing schema file: {}", schemaPathStr, e);
            messageHelper.sendMessage(chatId, "An error occurred while accessing the schema file \\(" + messageHelper.escapeMarkdownV2(e.getClass().getSimpleName()) + "\\)\\. Please try re\\-analyzing\\.");
            return;
        }

        String notification = "🧠 Got it\\! Asking the AI " + contextMessage + " \\(`" + messageHelper.escapeMarkdownV2(commitHash).substring(0, Math.min(7, commitHash.length())) + "`\\)\\.";
        if ("csv".equals(outputFormat)) {
            notification += "\n_Output will be in CSV file format\\._";
        } else if ("txt".equals(outputFormat)) {
            notification += "\n_Output will be in TXT file format\\._";
        }
        messageHelper.sendMessage(chatId, notification + "\\.\\.\\.");

        final Long finalTargetHistoryId = targetHistoryId;
        final long currentUserId = appUser.getId();
        final String finalOutputFormat = outputFormat;
        final String finalQueryForLlm = queryForLlm;

        taskExecutor.submit(() -> {
            String originalTelegramId = appUser.getTelegramId();
            org.slf4j.MDC.put("telegramId", originalTelegramId);
            try {
                Optional<String> generatedSqlOpt = geminiService.generateSqlFromSchema(schemaJson, finalQueryForLlm);
                if (generatedSqlOpt.isPresent()) {
                    String sql = generatedSqlOpt.get();
                    log.info("Generated SQL for History ID: {}. Output format: {}. SQL: {}", finalTargetHistoryId, finalOutputFormat, sql);
                    String escapedSql = messageHelper.escapeMarkdownV2(sql); // Use helper for escaping
                    botStateService.setLastGeneratedSql(currentUserId, finalTargetHistoryId + ":" + sql);

                    String callbackButtonData = String.format("execute_sql:%d:%s", finalTargetHistoryId, finalOutputFormat);

                    InlineKeyboardButton executeButton = InlineKeyboardButton.builder().text("🚀 Execute Query").callbackData(callbackButtonData).build();
                    InlineKeyboardMarkup keyboardMarkup = InlineKeyboardMarkup.builder().keyboardRow(List.of(executeButton)).build();

                    SendMessage resultMessage = SendMessage.builder().chatId(chatId)
                            .text("🤖 Generated SQL query:\n\n`" + escapedSql + "`\n\n*Disclaimer:* Review before execution\\.\nPress button to run\\.")
                            .parseMode("MarkdownV2").replyMarkup(keyboardMarkup).build();
                    messageHelper.tryExecute(resultMessage); // Use tryExecute from helper
                } else {
                    messageHelper.sendMessage(chatId, "Sorry, I couldn't generate an SQL query\\. Try rephrasing\\.");
                }
            } catch (Exception e) {
                log.error("Error during Gemini SQL generation", e);
                messageHelper.sendMessage(chatId, "An error occurred during SQL generation\\.");
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
        List<ScheduledQuery> schedules = scheduledQueryRepository.findByAppUserOrderByCreatedAtDesc(appUser);
        if (schedules.isEmpty()) {
            messageHelper.sendMessage(chatId, "You have no scheduled queries\\. Use `/schedule_query` to create one\\.");
            return;
        }
        StringBuilder sb = new StringBuilder("Your Scheduled Queries:\n");
        for (ScheduledQuery sq : schedules) {
            String repoUrl = sq.getAnalysisHistory() != null ? sq.getAnalysisHistory().getRepositoryUrl() : "N/A (History Missing!)";
            sb.append(String.format("ID: `%d` Name: `%s` %s\n  Repo: `%s`\n  CRON: `%s`\n  Next Run: `%s`\n  Output: %s\n\\-\\--\n",
                    sq.getId(),
                    messageHelper.escapeMarkdownV2(sq.getName() != null ? sq.getName() : "N/A"),
                    sq.isEnabled() ? "✅" : "⏸️",
                    messageHelper.escapeMarkdownV2(repoUrl),
                    messageHelper.escapeMarkdownV2(sq.getCronExpression()),
                    messageHelper.escapeMarkdownV2(sq.getNextExecutionAt() != null ? sq.getNextExecutionAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : "N/A"),
                    messageHelper.escapeMarkdownV2(sq.getOutputFormat())
            ));
            // TODO: Add inline buttons for managing each schedule (pause, resume, delete, edit)
        }
        messageHelper.sendMessage(chatId, sb.toString());
    }
}