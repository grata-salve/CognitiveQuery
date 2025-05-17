package com.example.cognitivequery.service;

import com.example.cognitivequery.bot.CognitiveQueryTelegramBot;
import com.example.cognitivequery.model.AnalysisHistory;
import com.example.cognitivequery.model.ScheduledQuery;
import com.example.cognitivequery.repository.ScheduledQueryRepository;
import com.example.cognitivequery.service.db.DynamicQueryExecutorService;
import lombok.RequiredArgsConstructor;
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
    // Мы не можем напрямую внедрить CognitiveQueryTelegramBot из-за циклической зависимости,
    // если бот также будет внедрять этот сервис.
    // Вместо этого бот должен будет вызывать публичный метод этого сервиса,
    // а этот сервис будет использовать ссылку на бота (если она передана) или
    // иметь собственный механизм для отправки уведомлений (что усложняет).
    // ПРОСТОЙ ВАРИАНТ: Сделать так, чтобы бот передавал ссылку на себя этому сервису после инициализации,
    // ИЛИ этот сервис должен быть компонентом, который бот может вызвать.
    // Для данного примера, предположим, что у нас есть ссылка на экземпляр бота.
    // Это можно решить через ApplicationContextAware или передачу при создании.
    // Либо, бот сам вызывает метод этого сервиса.
    // Для простоты, я сделаю так, что бот будет вызывать этот сервис.
    // НО для @Scheduled метода, он должен быть self-contained или иметь способ отправки сообщений.
    // Давайте сделаем так, что сервис будет получать ссылку на бота.

    private CognitiveQueryTelegramBot telegramBot; // Будет установлено через сеттер

    public void setTelegramBot(CognitiveQueryTelegramBot telegramBot) {
        this.telegramBot = telegramBot;
    }

    @Scheduled(fixedRate = 60000) // Каждую минуту (60 000 мс)
    @Transactional
    public void processScheduledQueries() {
        if (telegramBot == null) {
            log.warn("TelegramBot instance is not set in ScheduledQueryExecutionService. Skipping execution.");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        // Ищем запросы, у которых время выполнения уже наступило или наступает сейчас
        List<ScheduledQuery> dueQueries = scheduledQueryRepository.findAllByIsEnabledTrueAndNextExecutionAtBeforeOrNextExecutionAtEquals(now, now);

        if (!dueQueries.isEmpty()) {
            log.info("Found {} scheduled queries due for execution.", dueQueries.size());
        }

        for (ScheduledQuery scheduledQuery : dueQueries) {
            MDC.put("scheduledQueryId", String.valueOf(scheduledQuery.getId()));
            MDC.put("telegramId", scheduledQuery.getAppUser().getTelegramId()); // Для логов
            log.info("Executing scheduled query ID: {} for user: {}, chatID: {}",
                    scheduledQuery.getId(), scheduledQuery.getAppUser().getTelegramId(), scheduledQuery.getChatIdToNotify());

            AnalysisHistory history = scheduledQuery.getAnalysisHistory();
            if (history == null || !history.hasCredentials()) {
                log.warn("Scheduled query ID {} references history without credentials or null history. Disabling.", scheduledQuery.getId());
                scheduledQuery.setEnabled(false);
                // Не обновляем nextExecutionAt для отключенных задач, но сохраняем изменение isEnabled
                scheduledQueryRepository.save(scheduledQuery);
                telegramBot.sendMessage(scheduledQuery.getChatIdToNotify(),
                        "⚠️ Scheduled query '" + telegramBot.escapeMarkdownV2(scheduledQuery.getName() != null ? scheduledQuery.getName() : "ID: " + scheduledQuery.getId()) +
                                "' was disabled due to missing DB credentials in the associated analysis history\\.");
                MDC.remove("scheduledQueryId");
                MDC.remove("telegramId");
                continue;
            }

            try {
                DynamicQueryExecutorService.QueryResult result = queryExecutorService.executeQuery(
                        history.getDbHost(), history.getDbPort(), history.getDbName(),
                        history.getDbUser(), history.getDbPasswordEncrypted(), scheduledQuery.getSqlQuery());

                String queryName = telegramBot.escapeMarkdownV2(scheduledQuery.getName() != null ? scheduledQuery.getName() : "ID: " + scheduledQuery.getId());

                if (result.isSuccess()) {
                    telegramBot.sendMessage(scheduledQuery.getChatIdToNotify(), "📊 Results for scheduled query '" + queryName + "':");
                    if (result.type() == DynamicQueryExecutorService.QueryType.SELECT) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> resultData = (List<Map<String, Object>>) result.data();
                        String outputFormat = scheduledQuery.getOutputFormat();

                        if ("csv".equals(outputFormat)) {
                            telegramBot.sendSelectResultAsCsvFile(scheduledQuery.getChatIdToNotify(), resultData, history.getRepositoryUrl());
                        } else if ("txt".equals(outputFormat)) {
                            telegramBot.sendSelectResultAsTxtFile(scheduledQuery.getChatIdToNotify(), resultData, history.getRepositoryUrl());
                        } else { // text (default)
                            telegramBot.sendSelectResultAsTextInChat(scheduledQuery.getChatIdToNotify(), resultData, history.getRepositoryUrl());
                        }
                    } else { // UPDATE, INSERT, DELETE
                        telegramBot.sendMessage(scheduledQuery.getChatIdToNotify(),
                                "✅ Scheduled query '" + queryName +
                                        "' (non-SELECT) executed successfully\\. Rows affected: " + result.data());
                    }
                } else {
                    log.error("Scheduled query ID {} for user {} failed: {}", scheduledQuery.getId(), scheduledQuery.getAppUser().getTelegramId(), result.errorMessage());
                    telegramBot.sendMessage(scheduledQuery.getChatIdToNotify(),
                            "❌ Scheduled query '" + queryName +
                                    "' execution failed: " + telegramBot.escapeMarkdownV2(result.errorMessage()));
                    // Можно добавить логику для отключения после нескольких неудач
                }
            } catch (Exception e) {
                log.error("Unexpected error executing scheduled query ID {}", scheduledQuery.getId(), e);
                telegramBot.sendMessage(scheduledQuery.getChatIdToNotify(),
                        "❌ An unexpected error occurred while running scheduled query '" +
                                telegramBot.escapeMarkdownV2(scheduledQuery.getName() != null ? scheduledQuery.getName() : "ID: " + scheduledQuery.getId()) + "'\\.");
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
                    telegramBot.sendMessage(scheduledQuery.getChatIdToNotify(),
                            "❌ Scheduled query '" + telegramBot.escapeMarkdownV2(scheduledQuery.getName() != null ? scheduledQuery.getName() : "ID: " + scheduledQuery.getId()) +
                                    "' has an invalid CRON expression and has been disabled\\.");
                }
                scheduledQueryRepository.save(scheduledQuery);
                MDC.remove("scheduledQueryId");
                MDC.remove("telegramId");
            }
        }
    }
}