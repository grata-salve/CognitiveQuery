package com.example.cognitivequery.service;

import com.example.cognitivequery.bot.handler.TelegramMessageHelper;
import com.example.cognitivequery.model.AnalysisHistory;
import com.example.cognitivequery.model.ScheduledQuery;
import com.example.cognitivequery.repository.ScheduledQueryRepository;
import com.example.cognitivequery.service.db.DynamicQueryExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ScheduledQueryExecutionService {

    private final ScheduledQueryRepository scheduledQueryRepository;
    private final DynamicQueryExecutorService queryExecutorService;

    @Setter
    private TelegramMessageHelper messageHelper;

    // Run every minute
    @Scheduled(fixedRate = 60000)
    @Transactional
    public void processScheduledQueries() {
        if (messageHelper == null) {
            log.warn("TelegramMessageHelper instance is not set in ScheduledQueryExecutionService. Skipping execution.");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<ScheduledQuery> dueQueries = scheduledQueryRepository.findAllByIsEnabledTrueAndNextExecutionAtBeforeOrNextExecutionAtEquals(now, now);

        if (!dueQueries.isEmpty()) {
            log.info("Found {} scheduled queries due for execution.", dueQueries.size());
        }

        for (ScheduledQuery scheduledQuery : dueQueries) {
            MDC.put("scheduledQueryId", String.valueOf(scheduledQuery.getId()));
            MDC.put("telegramId", scheduledQuery.getAppUser().getTelegramId());
            log.info("Executing scheduled query ID: {} for user: {}, chatID: {}",
                    scheduledQuery.getId(), scheduledQuery.getAppUser().getTelegramId(), scheduledQuery.getChatIdToNotify());

            AnalysisHistory history = scheduledQuery.getAnalysisHistory();
            if (history == null || !history.hasCredentials()) {
                log.warn("Scheduled query ID {} references history without credentials or null history. Disabling.", scheduledQuery.getId());
                scheduledQuery.setEnabled(false);
                scheduledQueryRepository.save(scheduledQuery);
                messageHelper.sendMessage(scheduledQuery.getChatIdToNotify(),
                        "⚠️ Scheduled query '" + messageHelper.escapeMarkdownV2(scheduledQuery.getName() != null ? scheduledQuery.getName() : "ID: " + scheduledQuery.getId()) +
                                "' was disabled due to missing DB credentials in the associated analysis history\\.");
                MDC.remove("scheduledQueryId");
                MDC.remove("telegramId");
                continue;
            }

            try {
                DynamicQueryExecutorService.QueryResult result = queryExecutorService.executeQuery(
                        history.getDbHost(), history.getDbPort(), history.getDbName(),
                        history.getDbUser(), history.getDbPasswordEncrypted(),
                        scheduledQuery.getSqlQuery(),
                        scheduledQuery.getAppUser().isDataModificationEnabled()
                );

                String queryName = messageHelper.escapeMarkdownV2(scheduledQuery.getName() != null ? scheduledQuery.getName() : "ID: " + scheduledQuery.getId());

                if (result.isSuccess()) {
                    messageHelper.sendMessage(scheduledQuery.getChatIdToNotify(), "📊 Results for scheduled query '" + queryName + "':");
                    if (result.type() == DynamicQueryExecutorService.QueryType.SELECT) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> resultData = (List<Map<String, Object>>) result.data();
                        String outputFormat = scheduledQuery.getOutputFormat();

                        if ("csv".equals(outputFormat)) {
                            messageHelper.sendSelectResultAsCsvFile(scheduledQuery.getChatIdToNotify(), resultData, history.getRepositoryUrl());
                        } else if ("excel".equals(outputFormat)) {
                            messageHelper.sendSelectResultAsExcelFile(scheduledQuery.getChatIdToNotify(), resultData, history.getRepositoryUrl());
                        } else if ("txt".equals(outputFormat)) {
                            messageHelper.sendSelectResultAsTxtFile(scheduledQuery.getChatIdToNotify(), resultData, history.getRepositoryUrl());
                        } else { // text (default)
                            // Use the user's visualization setting for inline charts
                            boolean allowCharts = scheduledQuery.getAppUser().isVisualizationEnabled();
                            messageHelper.sendSelectResultAsTextInChat(scheduledQuery.getChatIdToNotify(), resultData, history.getRepositoryUrl(), allowCharts);
                        }
                    } else { // UPDATE, INSERT, DELETE
                        messageHelper.sendMessage(scheduledQuery.getChatIdToNotify(),
                                "✅ Scheduled query '" + queryName +
                                        "' (non-SELECT) executed successfully\\. Rows affected: " + result.data());
                    }
                } else {
                    log.error("Scheduled query ID {} for user {} failed: {}", scheduledQuery.getId(), scheduledQuery.getAppUser().getTelegramId(), result.errorMessage());
                    messageHelper.sendMessage(scheduledQuery.getChatIdToNotify(),
                            "❌ Scheduled query '" + queryName +
                                    "' execution failed: " + messageHelper.escapeMarkdownV2(result.errorMessage()));
                }
            } catch (Exception e) {
                log.error("Unexpected error executing scheduled query ID {}", scheduledQuery.getId(), e);
                messageHelper.sendMessage(scheduledQuery.getChatIdToNotify(),
                        "❌ An unexpected error occurred while running scheduled query '" +
                                messageHelper.escapeMarkdownV2(scheduledQuery.getName() != null ? scheduledQuery.getName() : "ID: " + scheduledQuery.getId()) + "'\\.");
            } finally {
                scheduledQuery.setLastExecutedAt(now);
                try {
                    CronExpression cron = CronExpression.parse(scheduledQuery.getCronExpression());
                    LocalDateTime nextExecution = cron.next(now);
                    scheduledQuery.setNextExecutionAt(nextExecution);
                    log.info("Scheduled query ID {} next execution set to: {}", scheduledQuery.getId(), nextExecution);
                } catch (IllegalArgumentException e) {
                    log.error("Invalid CRON expression for scheduled query ID {}: {}. Disabling.", scheduledQuery.getId(), scheduledQuery.getCronExpression(), e);
                    scheduledQuery.setEnabled(false);
                    messageHelper.sendMessage(scheduledQuery.getChatIdToNotify(),
                            "❌ Scheduled query '" + messageHelper.escapeMarkdownV2(scheduledQuery.getName() != null ? scheduledQuery.getName() : "ID: " + scheduledQuery.getId()) +
                                    "' has an invalid CRON expression and has been disabled\\.");
                }
                scheduledQueryRepository.save(scheduledQuery);
                MDC.remove("scheduledQueryId");
                MDC.remove("telegramId");
            }
        }
    }
}