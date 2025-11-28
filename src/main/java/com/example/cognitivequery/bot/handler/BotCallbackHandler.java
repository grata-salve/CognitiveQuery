package com.example.cognitivequery.bot.handler;

import com.example.cognitivequery.bot.model.ScheduleCreationState;
import com.example.cognitivequery.bot.service.BotStateService;
import com.example.cognitivequery.model.AnalysisHistory;
import com.example.cognitivequery.model.AppUser;
import com.example.cognitivequery.model.ScheduledQuery;
import com.example.cognitivequery.repository.AnalysisHistoryRepository;
import com.example.cognitivequery.repository.ScheduledQueryRepository;
import com.example.cognitivequery.repository.UserRepository;
import com.example.cognitivequery.service.db.DynamicQueryExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BotCallbackHandler {

    private final BotStateService botStateService;
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final DynamicQueryExecutorService queryExecutorService;
    private final BotInputHandler botInputHandler;
    private final ScheduledQueryRepository scheduledQueryRepository;
    private final UserRepository userRepository;

    public BotCallbackHandler(BotStateService botStateService,
                              AnalysisHistoryRepository analysisHistoryRepository,
                              DynamicQueryExecutorService queryExecutorService,
                              BotInputHandler botInputHandler,
                              ScheduledQueryRepository scheduledQueryRepository,
                              UserRepository userRepository) {
        this.botStateService = botStateService;
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.queryExecutorService = queryExecutorService;
        this.botInputHandler = botInputHandler;
        this.scheduledQueryRepository = scheduledQueryRepository;
        this.userRepository = userRepository;
    }

    public void handle(CallbackQuery callbackQuery, AppUser appUser,
                       TelegramMessageHelper messageHelper, ExecutorService taskExecutor) {
        String callbackData = callbackQuery.getData();
        long chatId = callbackQuery.getMessage().getChatId();
        long userId = appUser.getId(); // Internal User ID
        String callbackQueryId = callbackQuery.getId();

        String answerText = "Processing...";
        boolean showAlert = false;

        try {
            log.info("Received callback query from user ID {}: {}", userId, callbackData);

            if (callbackData == null) {
                answerText = "Error: Empty callback data.";
                showAlert = true;
                messageHelper.sendAnswerCallbackQuery(callbackQueryId, answerText, showAlert);
                return;
            }

            // 1. Execute SQL
            if (callbackData.startsWith("execute_sql:")) {
                handleExecuteSqlCallback(callbackQuery, appUser, chatId, userId, callbackData, messageHelper, taskExecutor);
                answerText = "Execution initiated...";
            }

            // 2. Schedule: History selection
            else if (callbackData.startsWith("sched_hist:")) {
                answerText = "History selected.";
                String historyIdStrCallback = callbackData.substring("sched_hist:".length());
                ScheduleCreationState state = botStateService.getScheduleCreationState(userId);
                if (state != null) {
                    botInputHandler.handleScheduleHistoryIdInput(chatId, userId, appUser, historyIdStrCallback, state, messageHelper);
                } else {
                    answerText = "Error: Schedule creation session expired.";
                    showAlert = true;
                    messageHelper.sendMessage(chatId, "Session for creating schedule has expired. Please start over with `/schedule_query`");
                }
            }

            // 3. Schedule: Format selection
            else if (callbackData.startsWith("sched_format:")) {
                answerText = "Format selected.";
                String formatCallback = callbackData.substring("sched_format:".length());
                ScheduleCreationState state = botStateService.getScheduleCreationState(userId);
                if (state != null) {
                    if (state.getAnalysisHistoryId() == null || state.getSqlQuery() == null || state.getCronExpression() == null || state.getChatIdToNotify() == null) {
                        answerText = "Error: Previous steps not completed.";
                        showAlert = true;
                        log.warn("User {} tried to set format for schedule via callback, but previous steps are missing. State: {}", userId, state);
                        messageHelper.sendMessage(chatId, "Some steps for schedule creation are missing. Please ensure all previous details are filled. You might need to start over using `/schedule_query`\\.");
                    } else {
                        state.setOutputFormat(formatCallback);
                        botInputHandler.saveScheduledQuery(chatId, userId, appUser, state, messageHelper);
                    }
                } else {
                    answerText = "Error: Schedule creation session expired.";
                    showAlert = true;
                    messageHelper.sendMessage(chatId, "Session for creating schedule has expired. Please start over with `/schedule_query`");
                }
            }

            // 4. Schedule: Frequency selection (Presets menu)
            else if (callbackData.startsWith("sched_freq:")) {
                String freq = callbackData.substring("sched_freq:".length());
                ScheduleCreationState state = botStateService.getScheduleCreationState(userId);

                if (state == null) {
                    answerText = "Error: Session expired.";
                    showAlert = true;
                    messageHelper.sendMessage(chatId, "Session for creating schedule has expired. Please start over.");
                } else {
                    // Handle "Pick Time" button
                    if ("picker".equals(freq)) {
                        answerText = "Select Hour";
                        botInputHandler.sendHourPicker(chatId, messageHelper);
                        return;
                    }
                    // Handle "Back" button
                    else if ("back_to_menu".equals(freq)) {
                        answerText = "Back to menu";
                        botInputHandler.handleScheduleSqlInput(chatId, userId, state.getSqlQuery(), state, messageHelper);
                        return;
                    }

                    String cronExpression = null;
                    String readableFreq = "";

                    switch (freq) {
                        case "daily_09":
                            cronExpression = "0 0 9 * * *";
                            readableFreq = "Daily at 09:00";
                            break;
                        case "daily_18":
                            cronExpression = "0 0 18 * * *";
                            readableFreq = "Daily at 18:00";
                            break;
                        case "weekly_mon":
                            cronExpression = "0 0 9 * * MON";
                            readableFreq = "Every Monday at 09:00";
                            break;
                        case "hourly":
                            cronExpression = "0 0 * * * *";
                            readableFreq = "Every hour";
                            break;
                        case "manual":
                            answerText = "Waiting for manual input...";
                            messageHelper.sendMessage(chatId, "⌨️ OK, please enter your custom **CRON expression** \\(e\\.g\\., `0 30 14 * * *`\\):");
                            return; // Wait for text input
                    }

                    if (cronExpression != null) {
                        answerText = "Frequency set.";
                        state.setCronExpression(cronExpression);
                        botStateService.setUserState(userId, com.example.cognitivequery.bot.model.UserState.WAITING_FOR_SCHEDULE_CHAT_ID);

                        messageHelper.sendMessage(chatId, "✅ Schedule set to: " + readableFreq + " (`" + cronExpression + "`)\\.\n" +
                                                          "Now, please enter the **Chat ID** where results should be sent\\. Type `this` to use the current chat\\.");
                    }
                }
            }

            // 5. Schedule: Hour Selection (Time Picker)
            else if (callbackData.startsWith("cron_h:")) {
                try {
                    int hour = Integer.parseInt(callbackData.substring("cron_h:".length()));
                    answerText = "Hour selected: " + hour;
                    botInputHandler.sendMinutePicker(chatId, hour, messageHelper);
                } catch (NumberFormatException e) {
                    log.error("Invalid hour format: {}", callbackData);
                }
            }

            // 6. Schedule: Minute Selection (Time Picker - Finish)
            else if (callbackData.startsWith("cron_m:")) {
                String[] parts = callbackData.substring("cron_m:".length()).split(":");
                try {
                    int hour = Integer.parseInt(parts[0]);
                    int minute = Integer.parseInt(parts[1]);

                    ScheduleCreationState state = botStateService.getScheduleCreationState(userId);
                    if (state != null) {
                        // Generate CRON: 0 min hour * * * (Daily)
                        String cron = String.format("0 %d %d * * *", minute, hour);

                        state.setCronExpression(cron);
                        botStateService.setUserState(userId, com.example.cognitivequery.bot.model.UserState.WAITING_FOR_SCHEDULE_CHAT_ID);

                        answerText = String.format("Time set to %02d:%02d", hour, minute);
                        messageHelper.sendMessage(chatId, String.format("✅ Schedule set to: **Daily at %02d:%02d** (`%s`)\n\nNow, enter **Chat ID** (or type `this`):", hour, minute, cron));
                    } else {
                        messageHelper.sendAnswerCallbackQuery(callbackQueryId, "Session expired", true);
                    }
                } catch (Exception e) {
                    log.error("Invalid time format: {}", callbackData);
                }
            }

            // 7. Credentials Reuse Choice
            else if (callbackData.startsWith("reuse_creds:")) {
                String action = callbackData.substring("reuse_creds:".length());

                if (botStateService.getUserState(userId) != com.example.cognitivequery.bot.model.UserState.WAITING_FOR_CREDENTIALS_REUSE_CHOICE) {
                    messageHelper.sendAnswerCallbackQuery(callbackQueryId, "Session expired or invalid state.", true);
                    return;
                }

                if ("yes".equals(action)) {
                    answerText = "✅ Using saved credentials.";
                    botInputHandler.handleCredentialsReuseChoice(chatId, userId, appUser, true, messageHelper, taskExecutor);
                } else {
                    answerText = "🆕 Entering new credentials.";
                    botInputHandler.handleCredentialsReuseChoice(chatId, userId, appUser, false, messageHelper, taskExecutor);
                }
            }

            // 8. Settings Toggle (/settings)
            else if (callbackData.startsWith("settings:")) {
                String action = callbackData.substring("settings:".length());

                if ("toggle_viz".equals(action)) {
                    boolean newState = !appUser.isVisualizationEnabled();
                    appUser.setVisualizationEnabled(newState);
                    userRepository.save(appUser);
                    answerText = "Visualization turned " + (newState ? "ON" : "OFF");
                } else if ("toggle_ai".equals(action)) {
                    boolean newState = !appUser.isAiInsightsEnabled();
                    appUser.setAiInsightsEnabled(newState);
                    userRepository.save(appUser);
                    answerText = "AI Insights turned " + (newState ? "ON" : "OFF");
                } else if ("toggle_mod".equals(action)) {
                    boolean newState = !appUser.isDataModificationEnabled();
                    appUser.setDataModificationEnabled(newState);
                    userRepository.save(appUser);
                    answerText = "Data Modification turned " + (newState ? "ON" : "OFF");
                } else if ("toggle_show_sql".equals(action)) {
                    boolean newState = !appUser.isShowSqlEnabled();
                    appUser.setShowSqlEnabled(newState);
                    userRepository.save(appUser);
                    answerText = "Show SQL turned " + (newState ? "ON" : "OFF");
                } else if ("toggle_dry_run".equals(action)) {
                    boolean newState = !appUser.isDryRunEnabled();
                    appUser.setDryRunEnabled(newState);
                    userRepository.save(appUser);
                    answerText = "Dry Run Mode turned " + (newState ? "ON" : "OFF");
                }

                refreshSettingsMessage((Message) callbackQuery.getMessage(), appUser, messageHelper);
            }

            // 9. Change DB Context (/list_schemas)
            else if (callbackData.startsWith("set_context:")) {
                String historyIdStr = callbackData.substring("set_context:".length());
                try {
                    long historyId = Long.parseLong(historyIdStr);
                    Optional<AnalysisHistory> historyOpt = analysisHistoryRepository.findById(historyId);

                    if (historyOpt.isPresent() && historyOpt.get().getAppUser().getId().equals(userId)) {
                        // 1. Change state
                        botStateService.setUserQueryContextHistoryId(userId, historyId);
                        botStateService.clearLastGeneratedSql(userId);

                        AnalysisHistory h = historyOpt.get();

                        // 2. Answer the callback
                        messageHelper.sendAnswerCallbackQuery(callbackQueryId, "Selected: " + extractRepoName(h.getRepositoryUrl()), false);

                        // 3. Redraw the menu with the new checkmark
                        List<AnalysisHistory> allHistory = analysisHistoryRepository.findByAppUserOrderByAnalyzedAtDesc(appUser);

                        // Grouping (as in BotCommandHandler)
                        Map<String, AnalysisHistory> uniqueRepos = allHistory.stream()
                                .collect(Collectors.groupingBy(
                                        AnalysisHistory::getRepositoryUrl,
                                        Collectors.collectingAndThen(
                                                Collectors.maxBy(java.util.Comparator.comparing(AnalysisHistory::getAnalyzedAt)),
                                                Optional::get
                                        )
                                ));

                        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder markupBuilder = InlineKeyboardMarkup.builder();

                        // Build the button list again
                        for (AnalysisHistory repo : uniqueRepos.values()) {
                            String name = extractRepoName(repo.getRepositoryUrl());
                            // Check if this repo is selected (now historyId is our choice)
                            boolean isActive = repo.getId().equals(historyId);
                            String icon = isActive ? "✅ " : "📁 ";

                            markupBuilder.keyboardRow(List.of(
                                    InlineKeyboardButton.builder()
                                            .text(icon + name)
                                            .callbackData("set_context:" + repo.getId())
                                            .build()
                            ));
                        }

                        // Update text and buttons
                        String newText = "📂 **Database Selection**\n\n" +
                                         "✅ Context switched to: `" + messageHelper.escapeMarkdownV2(extractRepoName(h.getRepositoryUrl())) + "`\n" +
                                         "Commands now apply to this database.";

                        EditMessageText edit = EditMessageText.builder()
                                .chatId(String.valueOf(chatId))
                                .messageId(callbackQuery.getMessage().getMessageId())
                                .text(newText)
                                .parseMode("Markdown")
                                .replyMarkup(markupBuilder.build())
                                .build();

                        messageHelper.editMessage(edit);

                    } else {
                        messageHelper.sendAnswerCallbackQuery(callbackQueryId, "Error: Schema not found.", true);
                    }
                } catch (NumberFormatException e) {
                    log.error("Invalid context ID: {}", historyIdStr);
                }
            }

            else if (callbackData.startsWith("sched_alert:")) {
                // If "Skip" button is pressed
                if (callbackData.endsWith("skip")) {
                    ScheduleCreationState state = botStateService.getScheduleCreationState(userId);
                    if (state != null) {
                        state.setAlertCondition(null); // No alert
                        // Proceed to format selection
                        botInputHandler.askForOutputFormat(chatId, userId, messageHelper);
                    }
                }
            }

            // 10. Scheduled Query Management
            else if (callbackData.startsWith("pause_sched:")) {
                answerText = handlePauseSchedule(callbackData, appUser, chatId, messageHelper);
                showAlert = answerText.startsWith("Error:");
            } else if (callbackData.startsWith("resume_sched:")) {
                answerText = handleResumeSchedule(callbackData, appUser, chatId, messageHelper);
                showAlert = answerText.startsWith("Error:");
            } else if (callbackData.startsWith("delete_sched:")) {
                answerText = handleDeleteSchedule(callbackData, appUser, chatId, messageHelper);
                showAlert = answerText.startsWith("Error:");
            } else {
                log.warn("Received unknown callback data: {}", callbackData);
                answerText = "Unknown action";
                showAlert = true;
            }
        } catch (Exception e) {
            log.error("Error processing callback query: " + callbackData, e);
            answerText = "Error processing action";
            showAlert = true;
        } finally {
            messageHelper.sendAnswerCallbackQuery(callbackQueryId, answerText, showAlert);
        }
    }

    // --- Private Methods ---

    private void handleExecuteSqlCallback(CallbackQuery callbackQuery, AppUser appUser, long chatId, long userId, String callbackData,
                                          TelegramMessageHelper messageHelper, ExecutorService taskExecutor) {
        String[] parts = callbackData.split(":");
        if (parts.length != 3) {
            messageHelper.sendAnswerCallbackQuery(callbackQuery.getId(), "Error: Invalid action format", true);
            return;
        }
        long historyIdToExecute;
        try {
            historyIdToExecute = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            messageHelper.sendAnswerCallbackQuery(callbackQuery.getId(), "Error: Invalid History ID", true);
            return;
        }
        String format = parts[2];

        String storedData = botStateService.getLastGeneratedSql(userId);
        String sqlToExecute = null;
        if (storedData != null && storedData.startsWith(historyIdToExecute + ":")) {
            sqlToExecute = storedData.substring(String.valueOf(historyIdToExecute).length() + 1);
        }

        if (sqlToExecute == null) {
            messageHelper.sendAnswerCallbackQuery(callbackQuery.getId(), "❌ Error: Query expired or invalid!", true);
            messageHelper.sendMessage(chatId, "Error: Could not find the SQL query to execute\\. Please generate it again using `/query`\\.");
            return;
        }

        Optional<AnalysisHistory> historyOpt = analysisHistoryRepository.findById(historyIdToExecute);
        if (historyOpt.isEmpty() || !Objects.equals(historyOpt.get().getAppUser().getId(), appUser.getId())) {
            messageHelper.sendAnswerCallbackQuery(callbackQuery.getId(), "❌ Error: Invalid history!", true);
            return;
        }

        AnalysisHistory history = historyOpt.get();
        if (!history.hasCredentials()) {
            messageHelper.sendAnswerCallbackQuery(callbackQuery.getId(), "❌ Error: DB credentials missing!", true);
            return;
        }

        messageHelper.sendAnswerCallbackQuery(callbackQuery.getId(), "Execution started...", false);
        messageHelper.sendMessage(chatId, "🚀 Executing SQL query\\.\\.\\.");
        botStateService.clearLastGeneratedSql(userId);

        final String finalSql = sqlToExecute;
        final String finalFormat = format;
        final String repoUrlForFile = history.getRepositoryUrl();
        final String telegramIdStr = appUser.getTelegramId();

        taskExecutor.submit(() -> {
            org.slf4j.MDC.put("telegramId", telegramIdStr);
            try {
                DynamicQueryExecutorService.QueryResult result = queryExecutorService.executeQuery(
                        history.getDbHost(), history.getDbPort(), history.getDbName(),
                        history.getDbUser(), history.getDbPasswordEncrypted(),
                        finalSql,
                        appUser.isDataModificationEnabled()
                );

                if (result.isSuccess()) {
                    if (result.type() == DynamicQueryExecutorService.QueryType.SELECT) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> resultData = (List<Map<String, Object>>) result.data();
                        if ("csv".equals(finalFormat)) {
                            messageHelper.sendSelectResultAsCsvFile(chatId, resultData, repoUrlForFile);
                        } else if ("txt".equals(finalFormat)) {
                            messageHelper.sendSelectResultAsTxtFile(chatId, resultData, repoUrlForFile);
                        } else if ("excel".equals(finalFormat)) {
                            messageHelper.sendSelectResultAsExcelFile(chatId, resultData, repoUrlForFile);
                        } else {
                            // Corrected: Added 4th argument (allowCharts)
                            messageHelper.sendSelectResultAsTextInChat(chatId, resultData, repoUrlForFile, appUser.isVisualizationEnabled());
                        }
                    } else if (result.type() == DynamicQueryExecutorService.QueryType.UPDATE) {
                        messageHelper.sendMessage(chatId, "✅ Query executed successfully\\. Rows affected: " + result.data());
                    }
                } else {
                    messageHelper.sendPlainTextMessage(chatId, "❌ Query execution failed: " + result.errorMessage());
                }
            } catch (Exception e) {
                log.error("Error executing SQL query '{}' for user {}", finalSql, userId, e);
                messageHelper.sendPlainTextMessage(chatId, "An unexpected error occurred during SQL execution: " + e.getMessage());
            } finally {
                org.slf4j.MDC.remove("telegramId");
            }
        });
    }

    private void refreshSettingsMessage(Message message, AppUser appUser, TelegramMessageHelper messageHelper) {
        String vizStatus = appUser.isVisualizationEnabled() ? "✅ ON" : "❌ OFF";
        String aiStatus = appUser.isAiInsightsEnabled() ? "✅ ON" : "❌ OFF";
        String modStatus = appUser.isDataModificationEnabled() ? "⚠️ ON" : "🔒 OFF";
        String sqlStatus = appUser.isShowSqlEnabled() ? "👁️ ON" : "❌ OFF";
        String dryStatus = appUser.isDryRunEnabled() ? "🚧 ON" : "❌ OFF";

        String newText = String.format("""
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
                // Row 1: Viz + AI
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder().text("📊 Viz").callbackData("settings:toggle_viz").build(),
                        InlineKeyboardButton.builder().text("💡 AI").callbackData("settings:toggle_ai").build()
                ))
                // Row 2: Show SQL + Dry Run
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder().text("👁️ Show SQL").callbackData("settings:toggle_show_sql").build(),
                        InlineKeyboardButton.builder().text("🚧 Dry Run").callbackData("settings:toggle_dry_run").build()
                ))
                // Row 3: Data Mod
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder().text("✏️ Data Mod").callbackData("settings:toggle_mod").build()
                ))
                .build();

        EditMessageText edit = EditMessageText.builder()
                .chatId(String.valueOf(message.getChatId()))
                .messageId(message.getMessageId())
                .text(newText)
                .parseMode("Markdown")
                .replyMarkup(markup)
                .build();

        messageHelper.editMessage(edit);
    }

    private String handlePauseSchedule(String callbackData, AppUser appUser, long chatId, TelegramMessageHelper messageHelper) {
        try {
            long scheduleId = Long.parseLong(callbackData.substring("pause_sched:".length()));
            Optional<ScheduledQuery> sqOpt = scheduledQueryRepository.findById(scheduleId);

            if (sqOpt.isEmpty() || !Objects.equals(sqOpt.get().getAppUser().getId(), appUser.getId())) {
                messageHelper.sendMessage(chatId, "Error: Schedule not found or you don't have permission.");
                return "Error: Invalid schedule ID.";
            }
            ScheduledQuery sq = sqOpt.get();
            if (!sq.isEnabled()) {
                return "Already paused.";
            }
            sq.setEnabled(false);
            scheduledQueryRepository.save(sq);
            messageHelper.sendMessage(chatId, "✅ Scheduled query `" + messageHelper.escapeMarkdownV2(sq.getName()) + "` \\(ID: " + sq.getId() + "\\) has been paused\\.");
            return "Paused.";
        } catch (Exception e) {
            log.error("Error pausing schedule: {}", callbackData, e);
            return "Error: Could not pause.";
        }
    }

    private String handleResumeSchedule(String callbackData, AppUser appUser, long chatId, TelegramMessageHelper messageHelper) {
        try {
            long scheduleId = Long.parseLong(callbackData.substring("resume_sched:".length()));
            Optional<ScheduledQuery> sqOpt = scheduledQueryRepository.findById(scheduleId);

            if (sqOpt.isEmpty() || !Objects.equals(sqOpt.get().getAppUser().getId(), appUser.getId())) {
                messageHelper.sendMessage(chatId, "Error: Schedule not found.");
                return "Error: Invalid schedule ID.";
            }
            ScheduledQuery sq = sqOpt.get();
            if (sq.isEnabled()) {
                return "Already active.";
            }
            sq.setEnabled(true);
            try {
                CronExpression cron = CronExpression.parse(sq.getCronExpression());
                LocalDateTime nextExecution = cron.next(LocalDateTime.now());
                sq.setNextExecutionAt(nextExecution);
            } catch (Exception e) {
                // ignore
            }
            scheduledQueryRepository.save(sq);
            messageHelper.sendMessage(chatId, "▶️ Scheduled query `" + messageHelper.escapeMarkdownV2(sq.getName()) + "` has been resumed\\.");
            return "Resumed.";
        } catch (Exception e) {
            log.error("Error resuming schedule", e);
            return "Error: Could not resume.";
        }
    }

    private String handleDeleteSchedule(String callbackData, AppUser appUser, long chatId, TelegramMessageHelper messageHelper) {
        try {
            long scheduleId = Long.parseLong(callbackData.substring("delete_sched:".length()));
            Optional<ScheduledQuery> sqOpt = scheduledQueryRepository.findById(scheduleId);

            if (sqOpt.isEmpty() || !Objects.equals(sqOpt.get().getAppUser().getId(), appUser.getId())) {
                messageHelper.sendMessage(chatId, "Error: Schedule not found.");
                return "Error: Invalid schedule ID.";
            }
            ScheduledQuery sq = sqOpt.get();
            scheduledQueryRepository.deleteById(scheduleId);
            messageHelper.sendMessage(chatId, "🗑️ Scheduled query `" + messageHelper.escapeMarkdownV2(sq.getName()) + "` has been deleted\\.");
            return "Deleted.";
        } catch (Exception e) {
            log.error("Error deleting schedule", e);
            return "Error: Could not delete.";
        }
    }

    private String extractRepoName(String url) {
        if (url == null) return "Unknown";
        return url.replace("https://github.com/", "").replace(".git", "");
    }
}