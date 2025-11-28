package com.example.cognitivequery.service;

import com.example.cognitivequery.bot.handler.TelegramMessageHelper;
import com.example.cognitivequery.model.AnalysisHistory;
import com.example.cognitivequery.model.ScheduledQuery;
import com.example.cognitivequery.repository.ScheduledQueryRepository;
import com.example.cognitivequery.service.db.DynamicQueryExecutorService;
import com.example.cognitivequery.service.llm.GeminiService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class ScheduledQueryExecutionService {

    private final ScheduledQueryRepository scheduledQueryRepository;
    private final DynamicQueryExecutorService queryExecutorService;
    private final GeminiService geminiService;

    @Setter
    private TelegramMessageHelper messageHelper;

    @Scheduled(fixedRate = 60000) // Every minute
    @Transactional
    public void processScheduledQueries() {
        if (messageHelper == null) {
            log.warn("TelegramMessageHelper instance is not set in ScheduledQueryExecutionService. Skipping execution.");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<ScheduledQuery> dueQueries = scheduledQueryRepository.findAllByIsEnabledTrueAndNextExecutionAtBeforeOrNextExecutionAtEquals(now, now);

        if (!dueQueries.isEmpty()) {
            log.info("Processing {} scheduled reports...", dueQueries.size());
        }

        for (ScheduledQuery scheduledQuery : dueQueries) {
            MDC.put("scheduledQueryId", String.valueOf(scheduledQuery.getId()));
            MDC.put("telegramId", scheduledQuery.getAppUser().getTelegramId());

            AnalysisHistory history = scheduledQuery.getAnalysisHistory();
            String reportName = scheduledQuery.getName() != null ? scheduledQuery.getName() : "Scheduled Report";

            if (history == null || !history.hasCredentials()) {
                scheduledQuery.setEnabled(false);
                scheduledQueryRepository.save(scheduledQuery);
                messageHelper.sendMessage(scheduledQuery.getChatIdToNotify(), "⚠️ Report '" + reportName + "' disabled: missing credentials.");
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

                if (result.isSuccess() && result.type() == DynamicQueryExecutorService.QueryType.SELECT) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> resultData = (List<Map<String, Object>>) result.data();

                    // --- ALERT CHECK ---
                    boolean isAlertTriggered = false;
                    if (scheduledQuery.getAlertCondition() != null && !scheduledQuery.getAlertCondition().isBlank()) {
                        try {
                            ObjectMapper mapper = new ObjectMapper();
                            mapper.findAndRegisterModules();
                            String json = mapper.writeValueAsString(resultData);

                            // Ask AI for condition check
                            boolean shouldNotify = geminiService.checkAlertCondition(scheduledQuery.getAlertCondition(), json);

                            if (!shouldNotify) {
                                log.info("Alert condition '{}' NOT met for query {}. Skipping.", scheduledQuery.getAlertCondition(), scheduledQuery.getId());
                                // SKIP SENDING AND PROCEED TO FINAL (time update)
                                continue;
                            }
                            isAlertTriggered = true;
                        } catch (Exception e) {
                            log.error("Failed to check alert condition", e);
                            // Fail-safe: if check fails, better to send the report
                        }
                    }
                    // --------------------------

                    String outputFormat = scheduledQuery.getOutputFormat();

                    // A. Header (Add 🚨 if alert triggered)
                    String titlePrefix = isAlertTriggered ? "🚨 **ALERT TRIGGERED**\n" : "";
                    messageHelper.sendMessage(scheduledQuery.getChatIdToNotify(), titlePrefix + "📑 **" + messageHelper.escapeMarkdownV2(reportName) + "**");

                    // B. Data
                    if ("csv".equals(outputFormat)) {
                        messageHelper.sendSelectResultAsCsvFile(scheduledQuery.getChatIdToNotify(), resultData, history.getRepositoryUrl());
                    } else if ("txt".equals(outputFormat)) {
                        messageHelper.sendSelectResultAsTxtFile(scheduledQuery.getChatIdToNotify(), resultData, history.getRepositoryUrl());
                    } else if ("excel".equals(outputFormat)) {
                        messageHelper.sendSelectResultAsExcelFile(scheduledQuery.getChatIdToNotify(), resultData, history.getRepositoryUrl());
                    } else { // text (default)
                        // Use the user's visualization setting for inline charts
                        boolean allowCharts = scheduledQuery.getAppUser().isVisualizationEnabled();
                        messageHelper.sendSelectResultAsTextInChat(scheduledQuery.getChatIdToNotify(), resultData, history.getRepositoryUrl(), allowCharts);
                    }

                    // C. AI Analysis
                    boolean aiEnabled = scheduledQuery.getAppUser().isAiInsightsEnabled();
                    if (aiEnabled && !resultData.isEmpty() && resultData.size() <= 50) {
                        try {
                            ObjectMapper mapper = new ObjectMapper();
                            mapper.findAndRegisterModules();
                            String jsonData = mapper.writeValueAsString(resultData);

                            Optional<String> analysis = geminiService.analyzeReportData(reportName, scheduledQuery.getSqlQuery(), jsonData);

                            if (analysis.isPresent()) {
                                messageHelper.sendPlainTextMessage(scheduledQuery.getChatIdToNotify(), "📊 Analysis:\n" + analysis.get());
                            }
                        } catch (Exception ex) {
                            log.error("Failed to generate report analysis", ex);
                        }
                    }

                } else if (!result.isSuccess()) {
                    messageHelper.sendPlainTextMessage(scheduledQuery.getChatIdToNotify(), "❌ Report failed: " + result.errorMessage());
                } else {
                    // Non-SELECT query executed (should not happen for scheduled queries, but as a fallback)
                    messageHelper.sendMessage(scheduledQuery.getChatIdToNotify(),
                            "✅ Scheduled query '" + messageHelper.escapeMarkdownV2(reportName) +
                            "' (non-SELECT) executed successfully\\. Rows affected: " + result.data());
                }

            } catch (Exception e) {
                log.error("Error executing report", e);
                messageHelper.sendMessage(scheduledQuery.getChatIdToNotify(),
                        "❌ An unexpected error occurred while running scheduled query '" +
                        messageHelper.escapeMarkdownV2(reportName) + "'\\.");
            } finally {
                // Update next execution time
                scheduledQuery.setLastExecutedAt(now);
                try {
                    CronExpression cron = CronExpression.parse(scheduledQuery.getCronExpression());
                    LocalDateTime nextExecution = cron.next(now);
                    scheduledQuery.setNextExecutionAt(nextExecution);
                } catch (Exception e) {
                    log.error("Invalid CRON expression for scheduled query ID {}. Disabling.", scheduledQuery.getId(), e);
                    scheduledQuery.setEnabled(false);
                }
                scheduledQueryRepository.save(scheduledQuery);
                MDC.remove("scheduledQueryId");
                MDC.remove("telegramId");
            }
        }
    }
}