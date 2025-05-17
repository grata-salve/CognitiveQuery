package com.example.cognitivequery.bot.handler;

import com.example.cognitivequery.bot.CognitiveQueryTelegramBot;
import com.example.cognitivequery.bot.model.AnalysisInputState;
import com.example.cognitivequery.bot.model.DbCredentialsInput;
import com.example.cognitivequery.bot.model.ScheduleCreationState;
import com.example.cognitivequery.bot.model.UserState;
import com.example.cognitivequery.bot.service.BotStateService;
import com.example.cognitivequery.model.AnalysisHistory;
import com.example.cognitivequery.model.AppUser;
import com.example.cognitivequery.model.ScheduledQuery;
import com.example.cognitivequery.repository.AnalysisHistoryRepository;
import com.example.cognitivequery.repository.ScheduledQueryRepository;
import com.example.cognitivequery.service.projectextractor.GitInfoService;
import com.example.cognitivequery.service.projectextractor.ProjectAnalyzerService;
import com.example.cognitivequery.service.security.EncryptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;


import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BotInputHandler {

    private final BotStateService botStateService;
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final ProjectAnalyzerService projectAnalyzerService;
    private final GitInfoService gitInfoService;
    private final EncryptionService encryptionService;
    private final ScheduledQueryRepository scheduledQueryRepository;
    private final BotCommandHandler botCommandHandler;

    public BotInputHandler(BotStateService botStateService,
                           AnalysisHistoryRepository analysisHistoryRepository,
                           ProjectAnalyzerService projectAnalyzerService,
                           GitInfoService gitInfoService,
                           EncryptionService encryptionService,
                           ScheduledQueryRepository scheduledQueryRepository,
                           BotCommandHandler botCommandHandler
    ) {
        this.botStateService = botStateService;
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.projectAnalyzerService = projectAnalyzerService;
        this.gitInfoService = gitInfoService;
        this.encryptionService = encryptionService;
        this.scheduledQueryRepository = scheduledQueryRepository;
        this.botCommandHandler = botCommandHandler;
    }

    public boolean processGeneralInput(Message message, AppUser appUser, UserState currentState,
                                       TelegramMessageHelper messageHelper, ExecutorService taskExecutor) {
        long chatId = message.getChatId();
        long userId = appUser.getId();
        String text = message.getText();

        switch (currentState) {
            case WAITING_FOR_REPO_URL:
                handleRepoUrlInput(chatId, userId, appUser, text, messageHelper, taskExecutor);
                return true;
            case WAITING_FOR_REPO_URL_FOR_CREDS:
                // Для WAITING_FOR_REPO_URL_FOR_CREDS не нужен taskExecutor, т.к. анализ не запускается
                handleRepoUrlForCredsInput(chatId, userId, text, messageHelper);
                return true;
            case WAITING_FOR_LLM_QUERY:
                botStateService.clearUserState(userId);
                botCommandHandler.processUserQuery(chatId, appUser, text, messageHelper, taskExecutor);
                return true;
            default:
                log.warn("processGeneralInput called with unhandled state: {} for user {}", currentState, userId);
                return false;
        }
    }

    // ИСПРАВЛЕНИЕ: Добавлен ExecutorService taskExecutor как параметр
    public boolean processCredentialsInput(Message message, AppUser appUser, UserState currentState,
                                           TelegramMessageHelper messageHelper, ExecutorService taskExecutor) {
        long chatId = message.getChatId();
        long userId = appUser.getId();
        String text = message.getText();

        DbCredentialsInput credsInputDirect = botStateService.getCredentialsInputState(userId);
        AnalysisInputState analysisInputFlow = botStateService.getAnalysisInputState(userId);

        DbCredentialsInput currentCreds = (analysisInputFlow != null && analysisInputFlow.getCredentials() != null)
                ? analysisInputFlow.getCredentials()
                : credsInputDirect;

        if (currentCreds == null) {
            log.error("Credentials or Analysis input state missing for user {}, state {}", userId, currentState);
            botStateService.clearAllUserStates(userId);
            messageHelper.sendMessage(chatId, "Something went wrong with the input process\\. Please start again\\.");
            return true;
        }

        try {
            switch (currentState) {
                case WAITING_FOR_DB_HOST:
                    currentCreds.setHost(text.trim());
                    botStateService.setUserState(userId, UserState.WAITING_FOR_DB_PORT);
                    messageHelper.sendMessage(chatId, "Got it\\. DB **port** \\(e\\.g\\., `5432`\\):");
                    break;
                // ... другие case для WAITING_FOR_DB_PORT, WAITING_FOR_DB_NAME, WAITING_FOR_DB_USER ...
                case WAITING_FOR_DB_PORT:
                    currentCreds.setPort(Integer.parseInt(text.trim()));
                    botStateService.setUserState(userId, UserState.WAITING_FOR_DB_NAME);
                    messageHelper.sendMessage(chatId, "OK\\. **Database name**:");
                    break;
                case WAITING_FOR_DB_NAME:
                    currentCreds.setName(text.trim());
                    botStateService.setUserState(userId, UserState.WAITING_FOR_DB_USER);
                    messageHelper.sendMessage(chatId, "DB **username**:");
                    break;
                case WAITING_FOR_DB_USER:
                    currentCreds.setUsername(text.trim());
                    botStateService.setUserState(userId, UserState.WAITING_FOR_DB_PASSWORD);
                    messageHelper.sendMessage(chatId, "Finally, DB **password**:");
                    break;
                case WAITING_FOR_DB_PASSWORD:
                    String plainPassword = text.trim();
                    Optional<String> encryptedPasswordOpt = encryptionService.encrypt(plainPassword);
                    if (encryptedPasswordOpt.isEmpty()) {
                        messageHelper.sendMessage(chatId, "Error encrypting password. Please try again or contact support.");
                        // Не сбрасываем состояние, чтобы пользователь мог попробовать снова или админ увидел ошибку.
                        // Или сбросить, чтобы не застрял? Зависит от политики. Пока оставим.
                        log.error("Failed to encrypt password for user {}", userId);
                        return true; // Ошибка обработана, но не успешно
                    }
                    currentCreds.setEncryptedPassword(encryptedPasswordOpt.get());
                    log.info("Credentials input complete for user {}.", userId);

                    if (analysisInputFlow != null && analysisInputFlow.getRepoUrl() != null) {
                        log.info("Proceeding to analysis after credential input for /analyze_repo flow for user {}", userId);
                        // ИСПРАВЛЕНИЕ: Передаем taskExecutor
                        performAnalysis(chatId, userId, appUser, analysisInputFlow, messageHelper, taskExecutor);
                    } else if (credsInputDirect != null && credsInputDirect.getAssociatedRepoUrl() != null) {
                        log.info("Processing credentials for /set_db_credentials flow for user {}", userId);
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
                            messageHelper.sendMessage(chatId, "✅ Database credentials updated successfully for `" + messageHelper.escapeMarkdownV2(credsInputDirect.getAssociatedRepoUrl()) + "`\\!");
                        } else {
                            log.warn("Cannot update credentials for user {}. No analysis history found for URL: {}", userId, credsInputDirect.getAssociatedRepoUrl());
                            messageHelper.sendMessage(chatId, "⚠️ Could not find a previous analysis for `" + messageHelper.escapeMarkdownV2(credsInputDirect.getAssociatedRepoUrl()) + "`\\. Credentials not saved\\. Please run `/analyze_repo` first for this repository\\.");
                        }
                    } else {
                        log.error("Cannot determine flow after password input for user {}. No active analysis or direct credential setting flow.", userId);
                        messageHelper.sendMessage(chatId, "An internal error occurred determining the next step\\. Please try again\\.");
                    }
                    botStateService.clearAllUserStates(userId); // Сбрасываем все состояния после успешного ввода или определения потока
                    break;
                default:
                    log.warn("Unexpected state {} during credential input for user {}.", currentState, userId);
                    botStateService.clearAllUserStates(userId);
                    return false;
            }
        } catch (NumberFormatException e) {
            messageHelper.sendMessage(chatId, "Invalid port number\\. Please enter a valid number\\.");
            // Не сбрасываем состояние, чтобы пользователь мог исправить ввод порта
        } catch (Exception e) {
            log.error("Error processing credential input for user {}", userId, e);
            botStateService.clearAllUserStates(userId); // Сбрасываем при общей ошибке
            messageHelper.sendMessage(chatId, "An error occurred during credential input: " + messageHelper.escapeMarkdownV2(e.getMessage()) + "\\. Please try again\\.");
        }
        return true;
    }


    private void handleRepoUrlForCredsInput(long chatId, long userId, String repoUrl, TelegramMessageHelper messageHelper) {
        DbCredentialsInput credsInput = botStateService.getOrCreateCredentialsInputState(userId);
        Matcher matcher = CognitiveQueryTelegramBot.BASIC_URL_PATTERN.matcher(repoUrl.trim());
        if (!matcher.matches()) {
            messageHelper.sendMessage(chatId, "The input doesn't look like a valid URL starting with `http://` or `https://`\\. Please enter the URL again\\.");
            return;
        }
        credsInput.setAssociatedRepoUrl(repoUrl.trim());
        log.info("Received repo URL for setting credentials: {} for user {}", credsInput.getAssociatedRepoUrl(), userId);
        botStateService.setUserState(userId, UserState.WAITING_FOR_DB_HOST);
        messageHelper.sendMessage(chatId, "OK\\. Now enter the DB **hostname** or **IP address** for `" + messageHelper.escapeMarkdownV2(credsInput.getAssociatedRepoUrl()) + "`:");
    }

    private void handleRepoUrlInput(long chatId, long userId, AppUser appUser, String repoUrl,
                                    TelegramMessageHelper messageHelper, ExecutorService taskExecutor) {
        AnalysisInputState input = botStateService.getAnalysisInputState(userId);
        if (input == null) {
            log.error("AnalysisInputState missing for user {} during repo URL input.", userId);
            botStateService.clearUserState(userId); // Сбрасываем состояние пользователя, т.к. input state потерян
            messageHelper.sendMessage(chatId, "An internal error occurred with the analysis session\\. Please try starting the analysis again with `/analyze_repo`\\.");
            return;
        }

        Matcher matcher = CognitiveQueryTelegramBot.GITHUB_URL_PATTERN.matcher(repoUrl.trim());
        if (!matcher.matches()) {
            messageHelper.sendMessage(chatId, "Invalid GitHub URL format\\. Please enter a valid GitHub repository URL \\(e\\.g\\., `https://github.com/owner/repo`\\)\\.");
            return; // Остаемся в состоянии WAITING_FOR_REPO_URL
        }

        input.setRepoUrl(repoUrl.trim());
        log.info("Handling repo URL input for analysis: '{}' for user {}", input.getRepoUrl(), userId);

        messageHelper.sendMessage(chatId, "Checking repository status\\.\\.\\.");
        Optional<String> currentCommitHashOpt = gitInfoService.getRemoteHeadCommitHash(input.getRepoUrl());
        // ... (остальная логика handleRepoUrlInput без изменений до вызова performAnalysis) ...
        // Примерно так:
        String currentCommitHash = currentCommitHashOpt.orElse("UNKNOWN_COMMIT");
        input.setCommitHash(currentCommitHash);

        Optional<AnalysisHistory> latestHistoryOpt = analysisHistoryRepository.findFirstByAppUserAndRepositoryUrlOrderByAnalyzedAtDesc(appUser, input.getRepoUrl());

        boolean needsAnalysis = true;
        boolean needsCredentials = true;
        String reason = "";
        String escapedValidatedUrl = messageHelper.escapeMarkdownV2(input.getRepoUrl());

        if (latestHistoryOpt.isPresent()) {
            AnalysisHistory latestHistory = latestHistoryOpt.get();
            String existingSchemaPathStr = latestHistory.getSchemaFilePath();

            if (!currentCommitHash.equals("UNKNOWN_COMMIT") && currentCommitHash.equals(latestHistory.getCommitHash())) {
                log.debug("Commit hash {} matches the latest analysis for URL {} by user {}", currentCommitHash, input.getRepoUrl(), userId);
                if (existingSchemaPathStr != null && !existingSchemaPathStr.isBlank()) {
                    try {
                        Path existingSchemaPath = Paths.get(existingSchemaPathStr);
                        if (Files.exists(existingSchemaPath) && Files.isRegularFile(existingSchemaPath)) {
                            needsAnalysis = false;
                            botStateService.setUserQueryContextHistoryId(appUser.getId(), latestHistory.getId());
                            messageHelper.sendMessage(chatId, "✅ This repository version \\(" + escapedValidatedUrl + "\\) was already analyzed\\. Query context set\\.\nSchema available at: `" + messageHelper.escapeMarkdownV2(existingSchemaPath.toString()) + "`");
                            botStateService.clearUserState(userId);
                            botStateService.clearAnalysisInputState(userId);
                        } else {
                            reason = "Previous result file missing at: " + existingSchemaPathStr;
                            log.warn(reason + " for user {}", userId);
                        }
                    } catch (Exception e) {
                        reason = "Invalid stored schema path: " + existingSchemaPathStr;
                        log.error(reason + " for user {}", userId, e);
                    }
                } else {
                    reason = "Schema file path missing in the latest history record.";
                    log.warn(reason + " for user {}", userId);
                }
            } else {
                reason = "Repository has been updated or commit hash mismatch. Latest analyzed: " + latestHistory.getCommitHash() + ", Current: " + currentCommitHash;
                log.info(reason + " for user {}", userId);
            }

            if (needsAnalysis && latestHistory.hasCredentials()) {
                log.info("Re-analysis needed for {}. Reusing credentials from history ID {} for user {}", input.getRepoUrl(), latestHistory.getId(), userId);
                input.setCredentials(DbCredentialsInput.fromHistory(latestHistory));
                needsCredentials = false;
            } else if (needsAnalysis) {
                reason += (reason.isEmpty() ? "" : " Also,") + " needs new credentials or previous credentials were not found.";
            }
        } else {
            reason = "New repository URL for this user.";
            log.info(reason + " User: {}", userId);
        }

        if (needsAnalysis) {
            log.info("Proceeding with analysis for URL {} by user {}. Reason: {}", input.getRepoUrl(), userId, reason);
            if (needsCredentials) {
                botStateService.setUserState(userId, UserState.WAITING_FOR_DB_HOST);
                AnalysisInputState currentInputState = botStateService.getOrCreateAnalysisInputState(userId);
                currentInputState.setRepoUrl(input.getRepoUrl());
                currentInputState.setCommitHash(input.getCommitHash());
                currentInputState.setCredentials(new DbCredentialsInput());

                messageHelper.sendMessage(chatId, "Analysis required for `" + escapedValidatedUrl + "`\\. " +
                        (reason.isEmpty() ? "" : "_(" + messageHelper.escapeMarkdownV2(reason) + ")_ ") +
                        "Please provide DB credentials\\.\nEnter DB **hostname** or **IP**:");
            } else {
                log.info("Proceeding directly to analysis for {} by user {} using reused credentials.", input.getRepoUrl(), userId);
                performAnalysis(chatId, userId, appUser, input, messageHelper, taskExecutor);
                // Состояние сбросится внутри performAnalysis или после его завершения, если это синхронно,
                // но т.к. performAnalysis асинхронный, сброс состояний из него может быть неверным.
                // Правильнее, если performAnalysis просто выполняет свою работу.
                // Сброс состояний WAITING_FOR_REPO_URL должен произойти здесь, т.к. мы перешли к следующему шагу (анализ или запрос кредов).
                // Однако, если нужны креды, мы переходим в WAITING_FOR_DB_HOST, так что не сбрасываем всё.
                // Если сразу анализ - то после performAnalysis (в его callback или Future) или если performAnalysis не кидает ошибок,
                // то можно условно сбросить здесь, но это рискованно.
                // Пока оставим сброс состояний в конце ветки "needsAnalysis == false" или в конце ввода кредов.
                // Здесь, если needsCredentials == false, то performAnalysis запускается, и мы ожидаем, что он завершится.
                // Сбрасывать userState и analysisInputState здесь может быть преждевременно, если performAnalysis еще не отработал.
                // НО! Мы уже отправили "Starting analysis...". Пользователь не должен больше ничего вводить для этого потока.
                // Поэтому, после запуска performAnalysis, текущий UserState.WAITING_FOR_REPO_URL можно считать завершенным.
                botStateService.clearUserState(userId); // Пользователь больше не в WAITING_FOR_REPO_URL
                botStateService.clearAnalysisInputState(userId); // Данные для этого конкретного анализа переданы
            }
        }
        // Если needsAnalysis == false, состояния уже были сброшены выше.
    }


    private void performAnalysis(long chatId, long userId, AppUser appUser, AnalysisInputState input,
                                 TelegramMessageHelper messageHelper, ExecutorService taskExecutor) {
        // ИСПРАВЛЕНИЕ: Проверка taskExecutor и логирование
        if (taskExecutor == null) {
            log.error("CRITICAL: TaskExecutor is NULL in performAnalysis for user {}. Analysis cannot proceed asynchronously. This is a bug in passing the executor.", userId);
            messageHelper.sendMessage(chatId, "❌ A critical internal error occurred (executor missing)\\. Analysis cannot start\\. Please contact support\\.");
            // Важно: нужно как-то обработать эту ситуацию. Нельзя просто продолжать.
            // Можно сбросить состояния, чтобы пользователь не застрял.
            botStateService.clearAllUserStates(userId);
            return;
        }

        String validatedUrl = input.getRepoUrl();
        String commitHashToSave = input.getCommitHash();
        DbCredentialsInput credentials = input.getCredentials();

        if (credentials == null || credentials.getEncryptedPassword() == null) {
            log.error("Credentials missing in performAnalysis for user {}.", userId);
            messageHelper.sendMessage(chatId, "Error: DB credentials missing for analysis\\.");
            botStateService.clearAnalysisInputState(userId);
            botStateService.clearUserState(userId);
            return;
        }

        String escapedUrl = messageHelper.escapeMarkdownV2(validatedUrl);
        String escapedHash = messageHelper.escapeMarkdownV2(commitHashToSave);
        String versionPart = commitHashToSave.equals("UNKNOWN_COMMIT") ? "" : " \\(version: `" + escapedHash.substring(0, Math.min(7, escapedHash.length())) + "`\\)";
        messageHelper.sendMessage(chatId, "⏳ Starting analysis for " + escapedUrl + versionPart + "\\.\\.\\. This may take a while\\.");

        Runnable analysisTask = () -> {
            String originalTelegramId = appUser.getTelegramId(); // Для MDC внутри потока
            org.slf4j.MDC.put("telegramId", originalTelegramId);
            org.slf4j.MDC.put("userId", String.valueOf(userId)); // Для логов
            org.slf4j.MDC.put("repoUrl", validatedUrl);

            Path resultSchemaPath = null;
            String oldSchemaPathToDelete = null;
            try {
                log.info("Analysis task started for user {}, repo {}", userId, validatedUrl);
                List<AnalysisHistory> oldHistories = analysisHistoryRepository.findByAppUserAndRepositoryUrl(appUser, validatedUrl);
                if (!oldHistories.isEmpty()) {
                    oldSchemaPathToDelete = oldHistories.stream()
                            .max(Comparator.comparing(AnalysisHistory::getAnalyzedAt))
                            .map(AnalysisHistory::getSchemaFilePath)
                            .orElse(null);
                }

                boolean cleanupClone = true;
                resultSchemaPath = projectAnalyzerService.analyzeAndProcessProject(validatedUrl, appUser.getTelegramId(), cleanupClone);
                log.info("Analysis successful for user {}, repo {}. Schema path: {}", userId, validatedUrl, resultSchemaPath);

                try {
                    AnalysisHistory newHistory = new AnalysisHistory(appUser, validatedUrl, commitHashToSave, resultSchemaPath.toString());
                    newHistory.setDbHost(credentials.getHost());
                    newHistory.setDbPort(credentials.getPort());
                    newHistory.setDbName(credentials.getName());
                    newHistory.setDbUser(credentials.getUsername());
                    newHistory.setDbPasswordEncrypted(credentials.getEncryptedPassword());
                    AnalysisHistory savedHistory = analysisHistoryRepository.save(newHistory);
                    log.info("Saved new analysis history record ID: {} for user {}", savedHistory.getId(), userId);

                    if (!oldHistories.isEmpty()) {
                        analysisHistoryRepository.deleteAll(oldHistories);
                        log.info("Deleted {} old history records for URL {} by user {}.", oldHistories.size(), validatedUrl, userId);
                    }

                    if (oldSchemaPathToDelete != null && !oldSchemaPathToDelete.isBlank() && !oldSchemaPathToDelete.equals(resultSchemaPath.toString())) {
                        try {
                            if (Files.deleteIfExists(Paths.get(oldSchemaPathToDelete))) {
                                log.info("Deleted previous schema file: {} for user {}", oldSchemaPathToDelete, userId);
                            }
                        } catch (Exception eDel) {
                            log.error("Failed to delete previous schema file: {} for user {}", oldSchemaPathToDelete, userId, eDel);
                        }
                    }
                    botStateService.setUserQueryContextHistoryId(appUser.getId(), savedHistory.getId());
                    messageHelper.sendMessage(chatId, "✅ Analysis successful\\! Schema saved\\.\nRepository: " + escapedUrl + "\nQuery context automatically set\\.\nUse `/query <your question>`");
                } catch (Exception dbEx) {
                    log.error("Failed to save/cleanup analysis results in DB for user {}, repo {}", userId, validatedUrl, dbEx);
                    messageHelper.sendMessage(chatId, "⚠️ Analysis done, but failed to save results/cleanup database records\\.");
                }
            } catch (Exception analysisEx) {
                log.error("Analysis failed for user {}, URL {}", userId, validatedUrl, analysisEx);
                String reasonMsg = analysisEx.getMessage() != null ? analysisEx.getMessage() : "Unknown error";
                if (analysisEx.getCause() != null && analysisEx.getCause().getMessage() != null) {
                    reasonMsg += " \\(Cause: " + analysisEx.getCause().getMessage() + "\\)";
                }
                messageHelper.sendMessage(chatId, "❌ Analysis failed for repository: " + escapedUrl + "\nReason: " + messageHelper.escapeMarkdownV2(reasonMsg));
            } finally {
                log.info("Analysis task finished for user {}, repo {}", userId, validatedUrl);
                org.slf4j.MDC.remove("repoUrl");
                org.slf4j.MDC.remove("userId");
                org.slf4j.MDC.remove("telegramId");
            }
        };
        taskExecutor.submit(analysisTask);
    }


    // --- Schedule Creation Input Handling ---
    // ... (остальная часть BotInputHandler без изменений, если они не затрагивают performAnalysis) ...
    // Код для processScheduleCreationInput и его вспомогательных методов остается как был,
    // если только performAnalysis не вызывался оттуда (а он не вызывался).
    // ... (handleScheduleNameInput, handleScheduleHistoryIdInput, etc.) ...
    // Убедитесь, что методы saveScheduledQuery и другие вспомогательные методы для расписаний
    // корректно сбрасывают состояния через botStateService.clearAllUserStates(userId) в нужных местах
    // (обычно по завершении потока или при ошибке, которую нельзя исправить).

    // ВАЖНО: Убедитесь, что все методы, вызывающие performAnalysis, передают ему не-null taskExecutor.
    // Сейчас это handleRepoUrlInput и processCredentialsInput.

    // --- Schedule Creation Input Handling (копипаст из предыдущего ответа, для полноты файла) ---
    public boolean processScheduleCreationInput(Message message, AppUser appUser, UserState currentState,
                                                TelegramMessageHelper messageHelper, ExecutorService taskExecutor) {
        long chatId = message.getChatId();
        long userId = appUser.getId();
        String text = message.getText();

        ScheduleCreationState state = botStateService.getScheduleCreationState(userId);
        if (state == null) {
            messageHelper.sendMessage(chatId, "Error: Schedule creation process not found\\. Please start again with `/schedule_query`\\.");
            botStateService.clearUserState(userId); // Сбрасываем только UserState, т.к. ScheduleCreationState уже null
            return true;
        }

        switch (currentState) {
            case WAITING_FOR_SCHEDULE_NAME:
                handleScheduleNameInput(chatId, userId, appUser, text, state, messageHelper);
                break;
            case WAITING_FOR_SCHEDULE_HISTORY_ID:
                handleScheduleHistoryIdInput(chatId, userId, appUser, text, state, messageHelper);
                break;
            case WAITING_FOR_SCHEDULE_SQL:
                handleScheduleSqlInput(chatId, userId, text, state, messageHelper);
                break;
            case WAITING_FOR_SCHEDULE_CRON:
                handleScheduleCronInput(chatId, userId, text, state, messageHelper);
                break;
            case WAITING_FOR_SCHEDULE_CHAT_ID:
                handleScheduleChatIdInput(chatId, userId, text, state, messageHelper);
                break;
            case WAITING_FOR_SCHEDULE_OUTPUT_FORMAT:
                handleScheduleOutputFormatInput(chatId, userId, appUser, text, state, messageHelper);
                break;
            default:
                log.warn("Unexpected state {} in processScheduleCreationInput for user {}", currentState, userId);
                botStateService.clearAllUserStates(userId);
                messageHelper.sendMessage(chatId, "An unexpected error occurred in scheduling\\. Please start over\\.");
                return false;
        }
        return true;
    }

    private void handleScheduleNameInput(long chatId, long userId, AppUser appUser, String name, ScheduleCreationState state, TelegramMessageHelper messageHelper) {
        if (name.trim().isEmpty()) {
            messageHelper.sendMessage(chatId, "Schedule name cannot be empty\\. Please enter a name:");
            return;
        }
        state.setName(name.trim());
        botStateService.setUserState(userId, UserState.WAITING_FOR_SCHEDULE_HISTORY_ID);

        List<AnalysisHistory> histories = analysisHistoryRepository.findByAppUserOrderByAnalyzedAtDesc(appUser);
        if (histories.isEmpty()) {
            messageHelper.sendMessage(chatId, "You have no analyzed repositories\\. Please use `/analyze_repo` first, then create a schedule\\.");
            botStateService.clearAllUserStates(userId);
            return;
        }

        List<AnalysisHistory> historiesWithCreds = histories.stream()
                .filter(AnalysisHistory::hasCredentials)
                .collect(Collectors.toList());

        if (historiesWithCreds.isEmpty()) {
            messageHelper.sendMessage(chatId, "You have analyzed repositories, but none of them have DB credentials set up\\. Please use `/set_db_credentials` first, then create a schedule\\.");
            botStateService.clearAllUserStates(userId);
            return;
        }

        StringBuilder sb = new StringBuilder("Great\\! Name set to: `" + messageHelper.escapeMarkdownV2(name.trim()) + "`\\.\n");
        sb.append("Please choose the **Analyzed Repository Schema** this query should run against, or type its ID:\n");

        var keyboardMarkupBuilder = InlineKeyboardMarkup.builder();
        int buttonCount = 0;
        final int MAX_BUTTONS_TO_SHOW = 5;

        for (AnalysisHistory h : historiesWithCreds) {
            if (buttonCount < MAX_BUTTONS_TO_SHOW) {
                String repoUrlDisplayName = h.getRepositoryUrl();
                try {
                    String[] parts = repoUrlDisplayName.split("/");
                    if (parts.length >= 2) {
                        repoUrlDisplayName = parts[parts.length - 2] + "/" + parts[parts.length - 1];
                    }
                } catch (Exception e) { /* use full URL */ }
                repoUrlDisplayName = repoUrlDisplayName.length() > 35 ? "..." + repoUrlDisplayName.substring(repoUrlDisplayName.length() - 32) : repoUrlDisplayName;
                String buttonText = String.format("ID %d: %s (%.7s)", h.getId(), repoUrlDisplayName, h.getCommitHash());
                buttonText = buttonText.length() > 60 ? buttonText.substring(0, 57) + "..." : buttonText;
                keyboardMarkupBuilder.keyboardRow(List.of(InlineKeyboardButton.builder().text(buttonText).callbackData("sched_hist:" + h.getId()).build()));
                buttonCount++;
            } else {
                break;
            }
        }
        if (historiesWithCreds.size() > MAX_BUTTONS_TO_SHOW || (buttonCount == 0 && !historiesWithCreds.isEmpty())) {
            sb.append("\n_More schemas available or not listed above\\. Use `/list_schemas` to see all IDs and enter an ID manually\\._");
        }
        sb.append("\nOr type the ID directly if you know it\\.");


        SendMessage listMessage = SendMessage.builder()
                .chatId(chatId)
                .text(sb.toString())
                .parseMode("MarkdownV2")
                .replyMarkup(buttonCount > 0 ? keyboardMarkupBuilder.build() : null)
                .build();
        messageHelper.tryExecute(listMessage);
    }

    public void handleScheduleHistoryIdInput(long chatId, long userId, AppUser appUser, String historyIdStr, ScheduleCreationState state, TelegramMessageHelper messageHelper) {
        try {
            long historyId = Long.parseLong(historyIdStr.trim());
            Optional<AnalysisHistory> historyOpt = analysisHistoryRepository.findByIdAndAppUser(historyId, appUser);
            if (historyOpt.isEmpty()) {
                messageHelper.sendMessage(chatId, "❌ Invalid Analysis History ID or it does not belong to you\\. Please try again or use `/list_schemas` to find the correct ID\\.");
                return;
            }
            if (!historyOpt.get().hasCredentials()) {
                messageHelper.sendMessage(chatId, "❌ The selected analysis history \\(ID: " + historyId + "\\) does not have database credentials set up\\. Please use `/set_db_credentials` for the repository `" + messageHelper.escapeMarkdownV2(historyOpt.get().getRepositoryUrl()) + "` first, or choose a different history\\.");
                return;
            }
            state.setAnalysisHistoryId(historyId);
            state.setAnalysisHistory(historyOpt.get());
            botStateService.setUserState(userId, UserState.WAITING_FOR_SCHEDULE_SQL);
            messageHelper.sendMessage(chatId, "✅ Analysis History set to ID: " + historyId + " \\(`" + messageHelper.escapeMarkdownV2(historyOpt.get().getRepositoryUrl()) + "`\\)\\.\nNow, please enter the **SQL query** to be scheduled \\(must be a `SELECT` query\\):");
        } catch (NumberFormatException e) {
            messageHelper.sendMessage(chatId, "❌ Invalid ID format\\. Please enter a numeric ID\\.");
        }
    }

    private void handleScheduleSqlInput(long chatId, long userId, String sql, ScheduleCreationState state, TelegramMessageHelper messageHelper) {
        if (sql.trim().isEmpty() || !sql.trim().toLowerCase().startsWith("select")) {
            messageHelper.sendMessage(chatId, "❌ Invalid SQL query\\. It should start with `SELECT` and not be empty\\. Please try again:");
            return;
        }
        state.setSqlQuery(sql.trim());
        botStateService.setUserState(userId, UserState.WAITING_FOR_SCHEDULE_CRON);
        messageHelper.sendMessage(chatId, "✅ SQL query set\\.\nNow, please enter the **CRON expression** for the schedule \\(e\\.g\\., `0 0 * * *` for daily at midnight, `0 12 * * MON-FRI` for noon on weekdays\\)\\.\nFor help with CRON, you can use an online generator like [crontab\\.guru](https://crontab.guru/)\\.");
    }

    private void handleScheduleCronInput(long chatId, long userId, String cronExpressionStr, ScheduleCreationState state, TelegramMessageHelper messageHelper) {
        try {
            CronExpression.parse(cronExpressionStr.trim());
            state.setCronExpression(cronExpressionStr.trim());
            botStateService.setUserState(userId, UserState.WAITING_FOR_SCHEDULE_CHAT_ID);
            messageHelper.sendMessage(chatId, "✅ CRON expression set to: `" + messageHelper.escapeMarkdownV2(cronExpressionStr.trim()) + "`\\.\nNow, please enter the **Chat ID** where results should be sent\\. Type `this` to use the current chat \\(ID: `" + chatId + "`\\)\\.");
        } catch (IllegalArgumentException e) {
            messageHelper.sendMessage(chatId, "❌ Invalid CRON expression: " + messageHelper.escapeMarkdownV2(e.getMessage()) + "\\. Please try again:");
        }
    }

    private void handleScheduleChatIdInput(long chatId, long userId, String chatIdStr, ScheduleCreationState state, TelegramMessageHelper messageHelper) {
        long targetChatId;
        if ("this".equalsIgnoreCase(chatIdStr.trim())) {
            targetChatId = chatId;
        } else {
            try {
                targetChatId = Long.parseLong(chatIdStr.trim());
            } catch (NumberFormatException e) {
                messageHelper.sendMessage(chatId, "❌ Invalid Chat ID format\\. Please enter a numeric ID or `this`\\.");
                return;
            }
        }
        state.setChatIdToNotify(targetChatId);
        botStateService.setUserState(userId, UserState.WAITING_FOR_SCHEDULE_OUTPUT_FORMAT);

        var keyboardBuilder = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(InlineKeyboardButton.builder().text("Text in Chat").callbackData("sched_format:text").build()))
                .keyboardRow(List.of(InlineKeyboardButton.builder().text("TXT File").callbackData("sched_format:txt").build()))
                .keyboardRow(List.of(InlineKeyboardButton.builder().text("CSV File").callbackData("sched_format:csv").build()));

        SendMessage messageWithKeyboard = SendMessage.builder()
                .chatId(chatId)
                .text("✅ Chat ID for notifications set to: `" + targetChatId + "`\\.\nFinally, choose the **output format** for the results or type one (`text`, `txt`, `csv`):")
                .parseMode("MarkdownV2")
                .replyMarkup(keyboardBuilder.build())
                .build();
        messageHelper.tryExecute(messageWithKeyboard);
    }

    private void handleScheduleOutputFormatInput(long chatId, long userId, AppUser appUser, String format, ScheduleCreationState state, TelegramMessageHelper messageHelper) {
        format = format.trim().toLowerCase();
        if (!List.of("text", "txt", "csv").contains(format)) {
            messageHelper.sendMessage(chatId, "❌ Invalid format\\. Please choose from `text`, `txt`, or `csv`, or use the buttons\\.");
            return;
        }
        state.setOutputFormat(format);
        saveScheduledQuery(chatId, userId, appUser, state, messageHelper);
    }

    public void saveScheduledQuery(long chatId, long userId, AppUser appUser, ScheduleCreationState state, TelegramMessageHelper messageHelper) {
        if (state.getAnalysisHistoryId() == null || state.getSqlQuery() == null || state.getCronExpression() == null || state.getChatIdToNotify() == null || state.getOutputFormat() == null || state.getName() == null) {
            messageHelper.sendMessage(chatId, "❌ Something went wrong, some information for the schedule is missing\\. Please start over with `/schedule_query`\\.");
            botStateService.clearAllUserStates(userId);
            return;
        }

        try {
            CronExpression cron = CronExpression.parse(state.getCronExpression());
            LocalDateTime nextExecution = cron.next(LocalDateTime.now());

            AnalysisHistory history = analysisHistoryRepository.findById(state.getAnalysisHistoryId())
                    .orElseThrow(() -> new IllegalStateException("AnalysisHistory not found when saving schedule: " + state.getAnalysisHistoryId() + " for user " + userId));


            ScheduledQuery newScheduledQuery = new ScheduledQuery(
                    appUser,
                    history,
                    state.getSqlQuery(),
                    state.getCronExpression(),
                    state.getChatIdToNotify(),
                    state.getOutputFormat(),
                    state.getName()
            );
            newScheduledQuery.setNextExecutionAt(nextExecution);
            newScheduledQuery.setEnabled(true);

            scheduledQueryRepository.save(newScheduledQuery);
            messageHelper.sendMessage(chatId, "✅ Scheduled query '" + messageHelper.escapeMarkdownV2(state.getName()) + "' created successfully\\! Next execution: `" + messageHelper.escapeMarkdownV2(nextExecution.toString()) + "`");
        } catch (IllegalArgumentException e) {
            messageHelper.sendMessage(chatId, "❌ Invalid CRON expression: `" + messageHelper.escapeMarkdownV2(state.getCronExpression()) + "`\\. " + messageHelper.escapeMarkdownV2(e.getMessage()) + "\\. Please try again\\.");
            botStateService.setUserState(userId, UserState.WAITING_FOR_SCHEDULE_CRON); // Возвращаем на шаг CRON
            // Не очищаем ScheduleCreationState, чтобы пользователь мог исправить только CRON
            messageHelper.sendMessage(chatId, "Please re\\-enter the CRON expression:");
            return;
        } catch (Exception e) {
            log.error("Error saving scheduled query for user {}", userId, e);
            messageHelper.sendMessage(chatId, "❌ An error occurred while saving the scheduled query: " + messageHelper.escapeMarkdownV2(e.getMessage()) + "\\. Please try again\\.");
            // При общей ошибке сбрасываем все состояния, т.к. непонятно, что пошло не так
        }
        botStateService.clearAllUserStates(userId);
    }
}