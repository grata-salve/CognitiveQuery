package com.example.cognitivequery.bot.handler;

import com.example.cognitivequery.bot.model.ScheduleCreationState;
import com.example.cognitivequery.bot.service.BotStateService;
import com.example.cognitivequery.model.AnalysisHistory;
import com.example.cognitivequery.model.AppUser;
import com.example.cognitivequery.repository.AnalysisHistoryRepository;
import com.example.cognitivequery.service.db.DynamicQueryExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

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
    private final BotInputHandler botInputHandler; // For schedule creation steps

    public BotCallbackHandler(BotStateService botStateService,
                              AnalysisHistoryRepository analysisHistoryRepository,
                              DynamicQueryExecutorService queryExecutorService,
                              BotInputHandler botInputHandler) {
        this.botStateService = botStateService;
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.queryExecutorService = queryExecutorService;
        this.botInputHandler = botInputHandler;
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
                answerText = "Execution initiated..."; // Toast for UI, actual message in chat
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
            }
            // TODO: Add handlers for schedule management buttons (pause_sched, resume_sched, delete_sched)
            // else if (callbackData.startsWith("pause_sched:")) { ... }
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
        String format = parts[2]; // "text", "csv", or "txt"

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

        // Acknowledge callback before starting long task
        messageHelper.sendAnswerCallbackQuery(callbackQuery.getId(), "Execution started...", false);
        messageHelper.sendMessage(chatId, "🚀 Executing SQL query\\.\\.\\.");
        botStateService.clearLastGeneratedSql(userId); // SQL is being used

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
                        } else { // Default "text"
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