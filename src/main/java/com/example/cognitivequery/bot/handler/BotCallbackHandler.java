package com.example.cognitivequery.bot.handler;

import com.example.cognitivequery.bot.model.ScheduleCreationState;
import com.example.cognitivequery.bot.service.BotStateService;
import com.example.cognitivequery.model.AnalysisHistory;
import com.example.cognitivequery.model.AppUser;
import com.example.cognitivequery.model.ScheduledQuery;
import com.example.cognitivequery.repository.AnalysisHistoryRepository;
import com.example.cognitivequery.repository.ScheduledQueryRepository;
import com.example.cognitivequery.service.db.DynamicQueryExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

@Service
@Slf4j
public class BotCallbackHandler {

    private final BotStateService botStateService;
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final DynamicQueryExecutorService queryExecutorService;
    private final BotInputHandler botInputHandler;
    private final ScheduledQueryRepository scheduledQueryRepository;

    public BotCallbackHandler(BotStateService botStateService,
                              AnalysisHistoryRepository analysisHistoryRepository,
                              DynamicQueryExecutorService queryExecutorService,
                              BotInputHandler botInputHandler,
                              ScheduledQueryRepository scheduledQueryRepository) {
        this.botStateService = botStateService;
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.queryExecutorService = queryExecutorService;
        this.botInputHandler = botInputHandler;
        this.scheduledQueryRepository = scheduledQueryRepository;
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

            if (callbackData.startsWith("execute_sql:")) {
                handleExecuteSqlCallback(callbackQuery, appUser, chatId, userId, callbackData, messageHelper, taskExecutor);
                answerText = "Execution initiated...";
            } else if (callbackData.startsWith("sched_hist:")) {
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
            } else if (callbackData.startsWith("sched_format:")) {
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
            } else if (callbackData.startsWith("pause_sched:")) {
                answerText = handlePauseSchedule(callbackData, appUser, chatId, messageHelper);
                showAlert = answerText.startsWith("Error:");
            } else if (callbackData.startsWith("resume_sched:")) {
                answerText = handleResumeSchedule(callbackData, appUser, chatId, messageHelper);
                showAlert = answerText.startsWith("Error:");
            } else if (callbackData.startsWith("delete_sched:")) {
                answerText = handleDeleteSchedule(callbackData, appUser, chatId, messageHelper);
                showAlert = answerText.startsWith("Error:");
            }
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
            messageHelper.sendAnswerCallbackQuery(callbackQueryId, answerText, showAlert);
        }
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
            // Corrected message with escaped parentheses
            messageHelper.sendMessage(chatId, "✅ Scheduled query `" + messageHelper.escapeMarkdownV2(sq.getName()) + "` \\(ID: " + sq.getId() + "\\) has been paused\\.");
            return "Paused.";
        } catch (NumberFormatException e) {
            log.error("Invalid schedule ID format in callback: {}", callbackData, e);
            return "Error: Invalid ID format.";
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
                messageHelper.sendMessage(chatId, "Error: Schedule not found or you don't have permission.");
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
                log.info("Resuming schedule ID {}. Next execution set to: {}", sq.getId(), nextExecution);
            } catch (IllegalArgumentException e) {
                log.error("Invalid CRON expression for schedule ID {} during resume: {}. Cannot set next execution.", sq.getId(), sq.getCronExpression(), e);
                messageHelper.sendMessage(chatId, "⚠️ Schedule resumed, but there was an issue recalculating next run due to CRON: " + messageHelper.escapeMarkdownV2(e.getMessage()));
            }
            scheduledQueryRepository.save(sq);
            // Corrected message with escaped parentheses and potentially for next run time
            String nextRunTime = sq.getNextExecutionAt() != null ? messageHelper.escapeMarkdownV2(sq.getNextExecutionAt().toString()) : "ASAP";
            messageHelper.sendMessage(chatId, "▶️ Scheduled query `" + messageHelper.escapeMarkdownV2(sq.getName()) + "` \\(ID: " + sq.getId() + "\\) has been resumed\\. Next run: `" + nextRunTime + "`");
            return "Resumed.";
        } catch (NumberFormatException e) {
            log.error("Invalid schedule ID format in callback: {}", callbackData, e);
            return "Error: Invalid ID format.";
        } catch (Exception e) {
            log.error("Error resuming schedule: {}", callbackData, e);
            return "Error: Could not resume.";
        }
    }

    private String handleDeleteSchedule(String callbackData, AppUser appUser, long chatId, TelegramMessageHelper messageHelper) {
        try {
            long scheduleId = Long.parseLong(callbackData.substring("delete_sched:".length()));
            Optional<ScheduledQuery> sqOpt = scheduledQueryRepository.findById(scheduleId);

            if (sqOpt.isEmpty() || !Objects.equals(sqOpt.get().getAppUser().getId(), appUser.getId())) {
                messageHelper.sendMessage(chatId, "Error: Schedule not found or you don't have permission.");
                return "Error: Invalid schedule ID.";
            }
            ScheduledQuery sq = sqOpt.get();
            scheduledQueryRepository.deleteById(scheduleId);
            // Corrected message with escaped parentheses
            messageHelper.sendMessage(chatId, "🗑️ Scheduled query `" + messageHelper.escapeMarkdownV2(sq.getName()) + "` \\(ID: " + sq.getId() + "\\) has been deleted\\.");
            return "Deleted.";
        } catch (NumberFormatException e) {
            log.error("Invalid schedule ID format in callback: {}", callbackData, e);
            return "Error: Invalid ID format.";
        } catch (Exception e) {
            log.error("Error deleting schedule: {}", callbackData, e);
            return "Error: Could not delete.";
        }
    }

    private void handleExecuteSqlCallback(CallbackQuery callbackQuery, AppUser appUser, long chatId, long userId, String callbackData,
                                          TelegramMessageHelper messageHelper, ExecutorService taskExecutor) {
        String[] parts = callbackData.split(":");
        if (parts.length != 3) { // execute_sql:historyId:format
            log.error("Invalid callback data format for execute_sql: {}", callbackData);
            messageHelper.sendAnswerCallbackQuery(callbackQuery.getId(), "Error: Invalid action format", true);
            return;
        }
        long historyIdToExecute;
        try {
            historyIdToExecute = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            log.error("Invalid history ID in execute_sql callback: {}", parts[1]);
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
            messageHelper.sendMessage(chatId, "❌ Cannot execute: Analysis history not found or invalid for your account\\.");
            return;
        }

        AnalysisHistory history = historyOpt.get();
        if (!history.hasCredentials()) {
            messageHelper.sendAnswerCallbackQuery(callbackQuery.getId(), "❌ Error: DB credentials missing!", true);
            messageHelper.sendMessage(chatId, "❌ Cannot execute: DB credentials missing for this analysis history\\. Use `/set_db_credentials`\\.");
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
                        history.getDbUser(), history.getDbPasswordEncrypted(), finalSql);

                if (result.isSuccess()) {
                    if (result.type() == DynamicQueryExecutorService.QueryType.SELECT) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> resultData = (List<Map<String, Object>>) result.data();
                        if ("csv".equals(finalFormat)) {
                            messageHelper.sendSelectResultAsCsvFile(chatId, resultData, repoUrlForFile);
                        } else if ("txt".equals(finalFormat)) {
                            messageHelper.sendSelectResultAsTxtFile(chatId, resultData, repoUrlForFile);
                        } else {
                            messageHelper.sendSelectResultAsTextInChat(chatId, resultData, repoUrlForFile);
                        }
                    } else if (result.type() == DynamicQueryExecutorService.QueryType.UPDATE) {
                        messageHelper.sendMessage(chatId, "✅ Query executed successfully\\. Rows affected: " + result.data());
                    } else {
                        messageHelper.sendMessage(chatId, "✅ Query executed, unknown result type\\.");
                    }
                } else {
                    messageHelper.sendMessage(chatId, "❌ Query execution failed: " + messageHelper.escapeMarkdownV2(result.errorMessage()));
                }
            } catch (Exception e) {
                log.error("Error executing SQL query '{}' for user {}", finalSql, userId, e);
                messageHelper.sendMessage(chatId, "An unexpected error occurred during SQL execution: " + messageHelper.escapeMarkdownV2(e.getMessage()));
            } finally {
                org.slf4j.MDC.remove("telegramId");
            }
        });
    }
}