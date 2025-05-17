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

    private final ExecutorService taskExecutor = Executors.newCachedThreadPool();

    public static final Pattern GITHUB_URL_PATTERN = Pattern.compile(
            "^(https?://)?(www\\.)?github\\.com/[\\w.-]+/[\\w.-]+(/)?(?:\\.git)?/?$", Pattern.CASE_INSENSITIVE);
    public static final Pattern BASIC_URL_PATTERN = Pattern.compile("^https?://.+", Pattern.CASE_INSENSITIVE);
    public static final String CSV_FLAG = "--csv";
    public static final String TXT_FLAG = "--txt";


    public CognitiveQueryTelegramBot(
            @Value("${telegram.bot.token}") String botToken,
            @Value("${telegram.bot.username}") String botUsername,
            UserRepository userRepository,
            BotStateService botStateService,
            BotCommandHandler botCommandHandler,
            BotInputHandler botInputHandler,
            BotCallbackHandler botCallbackHandler,
            ScheduledQueryExecutionService scheduledQueryExecutionService
    ) {
        super(botToken);
        this.botUsername = botUsername;
        this.userRepository = userRepository;
        this.botStateService = botStateService;
        this.botCommandHandler = botCommandHandler;
        this.botInputHandler = botInputHandler;
        this.botCallbackHandler = botCallbackHandler;
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
        commands.add(new BotCommand("connect_github", "Link GitHub account"));
        commands.add(new BotCommand("analyze_repo", "Analyze repository schema"));
        commands.add(new BotCommand("query", "Ask about data (add --csv or --txt for file output)"));
        commands.add(new BotCommand("list_schemas", "List analyzed repositories"));
        commands.add(new BotCommand("use_schema", "Set context for /query by URL"));
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
                // Pass taskExecutor to the callback handler if needed for asynchronous operations
                botCallbackHandler.handle(callbackQuery, appUser, messageHelper, taskExecutor);
            } catch (Exception e) {
                log.error("Unhandled exception during callback query processing: " + callbackQuery.getData(), e);
                messageHelper.sendAnswerCallbackQuery(callbackQuery.getId(), "Error processing action.", true);
            } finally {
                MDC.remove("telegramId");
            }
            return;
        }

        if (update.hasMessage() && update.getMessage().hasText()) {
            Message message = update.getMessage();
            long chatId = message.getChatId();
            long userIdTelegram = message.getFrom().getId(); // Telegram User ID
            String telegramIdStr = String.valueOf(userIdTelegram);

            MDC.put("telegramId", telegramIdStr);
            try {
                String messageText = message.getText();
                String userFirstName = message.getFrom().getFirstName();
                log.info("Received message from {}. Chat ID: {}, Telegram User ID: {}, Text: '{}'", userFirstName, chatId, userIdTelegram, messageText);

                AppUser appUser = findOrCreateUser(telegramIdStr);
                if (appUser == null) {
                    messageHelper.sendMessage(chatId, "Sorry, there was a problem accessing user data\\. Please try again later\\.");
                    return;
                }
                // Use the ID from our AppUser for states, as it might be the PK in our DB
                long internalUserId = appUser.getId();


                UserState currentState = botStateService.getUserState(internalUserId);
                log.debug("Current state for user (internal ID {}): {}", internalUserId, currentState);
                boolean processed = false;

                // 1. State-based input handling
                if (messageText.startsWith("/") && currentState != UserState.IDLE) {
                    log.warn("Command '{}' received while user {} was in state {}. Cancelling input flow.", messageText, internalUserId, currentState);
                    if (currentState.isScheduleCreationState()) {
                        messageHelper.sendMessage(chatId, "Schedule creation cancelled by new command\\.");
                    } else if (currentState.isCredentialInputState()){
                        messageHelper.sendMessage(chatId, "Credentials input cancelled by new command\\.");
                    } else if (currentState == UserState.WAITING_FOR_REPO_URL || currentState == UserState.WAITING_FOR_REPO_URL_FOR_CREDS) {
                        messageHelper.sendMessage(chatId, "Repository URL input cancelled by new command\\.");
                    } else if (currentState == UserState.WAITING_FOR_LLM_QUERY) {
                        messageHelper.sendMessage(chatId, "Query input cancelled by new command\\.");
                    }
                    botStateService.clearAllUserStates(internalUserId);
                    currentState = UserState.IDLE; // Update current state for subsequent logic
                }


                if (currentState != UserState.IDLE && !messageText.startsWith("/")) {
                    if (currentState.isScheduleCreationState()) {
                        processed = botInputHandler.processScheduleCreationInput(message, appUser, currentState, messageHelper, taskExecutor);
                    } else if (currentState.isCredentialInputState()) {
                        // FIX: pass taskExecutor
                        processed = botInputHandler.processCredentialsInput(message, appUser, currentState, messageHelper, taskExecutor);
                    } else {
                        processed = botInputHandler.processGeneralInput(message, appUser, currentState, messageHelper, taskExecutor);
                    }
                }


                // 2. Command handling
                if (!processed && messageText.startsWith("/")) {
                    log.debug("Processing message as a command: {}", messageText);
                    String[] parts = messageText.split("\\s+", 2);
                    String command = parts[0].toLowerCase();
                    String commandArgs = parts.length > 1 ? parts[1].trim() : "";

                    // Pass taskExecutor to the command handler if needed there
                    botCommandHandler.handle(message, appUser, command, commandArgs, userFirstName, messageHelper, taskExecutor);
                    processed = true;
                }

                // 3. Fallback
                if (!processed && currentState == UserState.IDLE) {
                    log.warn("Message '{}' from user (internal ID {}) was not processed by any handler in state {}.", messageText, internalUserId, currentState);
                    messageHelper.sendMessage(chatId, "I'm not sure what to do with that\\. Try `/help`\\.");
                }

            } catch (Exception e) {
                log.error("Unhandled exception during update processing for message: " + (message != null ? message.getText() : "N/A"), e);
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
                // If AppUser should have a default githubId or other fields, set them here
                return userRepository.save(newUser);
            });
        } catch (Exception e) {
            log.error("Database error fetching/creating user for Telegram ID: {}.", telegramIdStr, e);
            return null;
        }
    }

}