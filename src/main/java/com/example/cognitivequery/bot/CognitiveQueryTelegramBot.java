package com.example.cognitivequery.bot;

import com.example.cognitivequery.bot.handler.BotCallbackHandler;
import com.example.cognitivequery.bot.handler.BotCommandHandler;
import com.example.cognitivequery.bot.handler.BotInputHandler;
import com.example.cognitivequery.bot.handler.TelegramMessageHelper;
import com.example.cognitivequery.bot.model.UserState;
import com.example.cognitivequery.bot.service.BotStateService;
import com.example.cognitivequery.model.AppUser;
import com.example.cognitivequery.repository.UserRepository;
import com.example.cognitivequery.service.ScheduledQueryExecutionService;
import com.example.cognitivequery.service.llm.GeminiService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
@Slf4j
public class CognitiveQueryTelegramBot extends TelegramLongPollingBot {

    private final String botUsername;
    private final UserRepository userRepository;

    private final BotStateService botStateService;
    private final BotCommandHandler botCommandHandler;
    private final BotInputHandler botInputHandler;
    private final BotCallbackHandler botCallbackHandler;
    @Getter
    private final TelegramMessageHelper messageHelper;
    private final ScheduledQueryExecutionService scheduledQueryExecutionService;
    private final GeminiService geminiService;

    // Executor for offloading long-running tasks like DB queries and LLM calls
    private final ExecutorService taskExecutor = Executors.newCachedThreadPool();

    public static final Pattern GITHUB_URL_PATTERN = Pattern.compile(
            "^(https?://)?(www\\.)?github\\.com/[\\w.-]+/[\\w.-]+(/)?(?:\\.git)?/?$", Pattern.CASE_INSENSITIVE);
    public static final Pattern BASIC_URL_PATTERN = Pattern.compile("^https?://.+", Pattern.CASE_INSENSITIVE);
    public static final String CSV_FLAG = "--csv";
    public static final String TXT_FLAG = "--txt";
    public static final String EXCEL_FLAG = "--excel";


    public CognitiveQueryTelegramBot(
            @Value("${telegram.bot.token}") String botToken,
            @Value("${telegram.bot.username}") String botUsername,
            UserRepository userRepository,
            BotStateService botStateService,
            BotCommandHandler botCommandHandler,
            BotInputHandler botInputHandler,
            BotCallbackHandler botCallbackHandler,
            ScheduledQueryExecutionService scheduledQueryExecutionService,
            GeminiService geminiService
    ) {
        super(botToken);
        this.botUsername = botUsername;
        this.userRepository = userRepository;
        this.botStateService = botStateService;
        this.botCommandHandler = botCommandHandler;
        this.botInputHandler = botInputHandler;
        this.botCallbackHandler = botCallbackHandler;
        this.geminiService = geminiService;
        this.messageHelper = new TelegramMessageHelper(this); // Important: 'this' is passed for the execute method
        this.scheduledQueryExecutionService = scheduledQueryExecutionService;
        log.info("Telegram Bot initialized. Username: {}", botUsername);
    }

    @PostConstruct
    public void init() {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(this);
            log.info("Telegram Bot registered successfully!");
            setBotCommands();
            this.scheduledQueryExecutionService.setMessageHelper(this.messageHelper);
        } catch (TelegramApiException e) {
            log.error("Error registering Telegram Bot or setting commands", e);
        }
    }

    @PreDestroy
    public void shutdownExecutor() {
        log.info("Shutting down task executor service...");
        taskExecutor.shutdown();
        try {
            if (!taskExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                taskExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            taskExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Task executor service shut down.");
    }

    private void setBotCommands() {
        List<BotCommand> commands = new ArrayList<>();
        commands.add(new BotCommand("start", "Start interaction"));
        commands.add(new BotCommand("settings", "Configure charts & AI options"));
        commands.add(new BotCommand("connect_github", "Link GitHub account"));
        commands.add(new BotCommand("analyze_repo", "Analyze repository schema"));
        commands.add(new BotCommand("query", "Ask about data (add --csv, --txt or --excel for file output)"));
        commands.add(new BotCommand("list_schemas", "List analyzed repositories"));
        commands.add(new BotCommand("use_schema", "Set context for /query by URL"));
        commands.add(new BotCommand("show_schema", "Visualize database structure"));
        commands.add(new BotCommand("set_db_credentials", "Set DB credentials for a repo"));
        commands.add(new BotCommand("schedule_query", "Create a new scheduled query"));
        commands.add(new BotCommand("list_scheduled_queries", "List your scheduled queries"));
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
        // 1. CallbackQuery handling
        if (update.hasCallbackQuery()) {
            CallbackQuery callbackQuery = update.getCallbackQuery();
            String telegramIdStr = String.valueOf(callbackQuery.getFrom().getId());
            MDC.put("telegramId", telegramIdStr);
            try {
                AppUser appUser = findOrCreateUser(telegramIdStr);
                if (appUser == null) {
                    messageHelper.sendAnswerCallbackQuery(callbackQuery.getId(), "User data error.", true);
                    return;
                }
                botCallbackHandler.handle(callbackQuery, appUser, messageHelper, taskExecutor);
            } catch (Exception e) {
                log.error("Unhandled exception during callback query processing: " + callbackQuery.getData(), e);
                messageHelper.sendAnswerCallbackQuery(callbackQuery.getId(), "Error processing action.", true);
            } finally {
                MDC.remove("telegramId");
            }
            return;
        }

        // 2. Message handling (Text or Voice)
        if (update.hasMessage()) {
            Message message = update.getMessage();
            long chatId = message.getChatId();
            long userIdTelegram = message.getFrom().getId();
            String telegramIdStr = String.valueOf(userIdTelegram);

            MDC.put("telegramId", telegramIdStr);
            try {
                AppUser appUser = findOrCreateUser(telegramIdStr);
                if (appUser == null) {
                    messageHelper.sendMessage(chatId, "Sorry, there was a problem accessing user data\\. Please try again later\\.");
                    return;
                }
                long internalUserId = appUser.getId();
                UserState currentState = botStateService.getUserState(internalUserId);

                String textToProcess = null;

                // --- LOGIC 1: VOICE MESSAGES ---
                if (message.hasVoice()) {
                    messageHelper.sendPlainTextMessage(chatId, "👂 Listening...");
                    try {
                        // 1. Get file info
                        var fileId = message.getVoice().getFileId();
                        var getFileMethod = new org.telegram.telegrambots.meta.api.methods.GetFile(fileId);
                        org.telegram.telegrambots.meta.api.objects.File telegramFile = execute(getFileMethod);

                        // 2. Download file
                        java.io.File file = downloadFile(telegramFile);
                        byte[] audioBytes = java.nio.file.Files.readAllBytes(file.toPath());

                        // 3. Transcribe via Gemini
                        java.util.Optional<String> transcriptOpt = geminiService.transcribeAudio(audioBytes);

                        if (transcriptOpt.isPresent()) {
                            textToProcess = transcriptOpt.get();
                            messageHelper.sendPlainTextMessage(chatId, "🗣️ You said: \"" + textToProcess + "\"");
                        } else {
                            messageHelper.sendPlainTextMessage(chatId, "❌ Could not recognize speech.");
                            return;
                        }
                        // Delete temporary file
                        file.delete();

                    } catch (Exception e) {
                        log.error("Voice processing failed", e);
                        messageHelper.sendPlainTextMessage(chatId, "❌ Error processing voice message.");
                        return;
                    }
                }
                // --- LOGIC 2: TEXT MESSAGES ---
                else if (message.hasText()) {
                    textToProcess = message.getText();
                } else {
                    // Ignore stickers, photos, etc.
                    return;
                }

                // If text is empty (e.g., transcription error), exit
                if (textToProcess == null) return;

                log.info("Processing input from {}. Text: '{}'", message.getFrom().getFirstName(), textToProcess);

                boolean processed = false;

                // --- LOGIC 3: STATE HANDLING (Password input, URL, Schedule menu) ---
                // If a command (/start) is received while waiting for input (password, etc.) -> reset state
                if (textToProcess.startsWith("/") && currentState != UserState.IDLE) {
                    log.warn("Command '{}' received while user {} was in state {}. Cancelling input flow.", textToProcess, internalUserId, currentState);
                    if (currentState.isScheduleCreationState()) messageHelper.sendMessage(chatId, "Schedule creation cancelled.");
                    else if (currentState.isCredentialInputState()) messageHelper.sendMessage(chatId, "Credentials input cancelled.");

                    botStateService.clearAllUserStates(internalUserId);
                    currentState = UserState.IDLE;
                }

                if (currentState != UserState.IDLE && !textToProcess.startsWith("/")) {
                    // Pass the input to InputHandler (textToProcess is either the message text or the voice transcription)
                    if (message.hasVoice()) {
                        // Create a "fake" message text for InputHandler to consume
                        message.setText(textToProcess);
                    }

                    if (currentState.isScheduleCreationState()) {
                        processed = botInputHandler.processScheduleCreationInput(message, appUser, currentState, messageHelper, taskExecutor);
                    } else if (currentState.isCredentialInputState()) {
                        processed = botInputHandler.processCredentialsInput(message, appUser, currentState, messageHelper, taskExecutor);
                    } else {
                        processed = botInputHandler.processGeneralInput(message, appUser, currentState, messageHelper, taskExecutor);
                    }
                }

                // --- LOGIC 4: EXPLICIT COMMANDS (starting with /) ---
                if (!processed && textToProcess.startsWith("/")) {
                    String[] parts = textToProcess.split("\\s+", 2);
                    String command = parts[0].toLowerCase();
                    String commandArgs = parts.length > 1 ? parts[1].trim() : "";

                    botCommandHandler.handle(message, appUser, command, commandArgs, message.getFrom().getFirstName(), messageHelper, taskExecutor);
                    processed = true;
                }

                // --- LOGIC 5: SMART ROUTER (Natural Language & Voice) ---
                // If it's neither a command nor input data, treat it as an AI query
                if (!processed && currentState == UserState.IDLE) {
                    log.info("Routing natural language input: {}", textToProcess);
                    // Invoke the "Brain" to determine intent (Query, Schema, Settings, etc.)
                    botCommandHandler.handleNaturalLanguage(chatId, appUser, textToProcess, messageHelper, taskExecutor);
                }

            } catch (Exception e) {
                log.error("Unhandled exception during update processing", e);
                messageHelper.sendMessage(chatId, "An unexpected error occurred\\.");
            } finally {
                MDC.remove("telegramId");
            }
        }
    }

    private AppUser findOrCreateUser(String telegramIdStr) {
        try {
            return userRepository.findByTelegramId(telegramIdStr).orElseGet(() -> {
                log.info("User not found, creating new for Telegram ID: {}", telegramIdStr);
                AppUser newUser = new AppUser(telegramIdStr);
                return userRepository.save(newUser);
            });
        } catch (Exception e) {
            log.error("Database error fetching/creating user for Telegram ID: {}.", telegramIdStr, e);
            return null;
        }
    }

}