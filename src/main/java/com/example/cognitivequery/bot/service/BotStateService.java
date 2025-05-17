package com.example.cognitivequery.bot.service;

import com.example.cognitivequery.bot.model.AnalysisInputState;
import com.example.cognitivequery.bot.model.DbCredentialsInput;
import com.example.cognitivequery.bot.model.ScheduleCreationState;
import com.example.cognitivequery.bot.model.UserState;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BotStateService {

    private final Map<Long, UserState> userStates = new ConcurrentHashMap<>();
    private final Map<Long, AnalysisInputState> analysisInputStates = new ConcurrentHashMap<>();
    private final Map<Long, DbCredentialsInput> credentialsInputStates = new ConcurrentHashMap<>();
    private final Map<Long, Long> userQueryContextHistoryIds = new ConcurrentHashMap<>();
    private final Map<Long, String> lastGeneratedSqlMap = new ConcurrentHashMap<>();
    private final Map<Long, ScheduleCreationState> scheduleCreationStates = new ConcurrentHashMap<>();

    // UserState
    public UserState getUserState(long userId) {
        return userStates.getOrDefault(userId, UserState.IDLE);
    }

    public void setUserState(long userId, UserState state) {
        userStates.put(userId, state);
    }

    public void clearUserState(long userId) {
        userStates.remove(userId);
    }

    // AnalysisInputState
    public AnalysisInputState getAnalysisInputState(long userId) {
        return analysisInputStates.get(userId);
    }
    public AnalysisInputState getOrCreateAnalysisInputState(long userId) {
        return analysisInputStates.computeIfAbsent(userId, k -> new AnalysisInputState());
    }
    public void setAnalysisInputState(long userId, AnalysisInputState state) {
        analysisInputStates.put(userId, state);
    }
    public void clearAnalysisInputState(long userId) {
        analysisInputStates.remove(userId);
    }

    // DbCredentialsInput
    public DbCredentialsInput getCredentialsInputState(long userId) {
        return credentialsInputStates.get(userId);
    }
    public DbCredentialsInput getOrCreateCredentialsInputState(long userId) {
        return credentialsInputStates.computeIfAbsent(userId, k -> new DbCredentialsInput());
    }
    public void setCredentialsInputState(long userId, DbCredentialsInput state) {
        credentialsInputStates.put(userId, state);
    }
    public void clearCredentialsInputState(long userId) {
        credentialsInputStates.remove(userId);
    }

    // UserQueryContextHistoryId
    public Long getUserQueryContextHistoryId(long userId) {
        return userQueryContextHistoryIds.get(userId);
    }
    public void setUserQueryContextHistoryId(long userId, Long historyId) {
        userQueryContextHistoryIds.put(userId, historyId);
    }
    public void clearUserQueryContextHistoryId(long userId) {
        userQueryContextHistoryIds.remove(userId);
    }

    // LastGeneratedSql
    public String getLastGeneratedSql(long userId) {
        return lastGeneratedSqlMap.get(userId);
    }
    public void setLastGeneratedSql(long userId, String historyIdAndSql) {
        lastGeneratedSqlMap.put(userId, historyIdAndSql);
    }
    public void clearLastGeneratedSql(long userId) {
        lastGeneratedSqlMap.remove(userId);
    }

    // ScheduleCreationState
    public ScheduleCreationState getScheduleCreationState(long userId) {
        return scheduleCreationStates.get(userId);
    }
    public ScheduleCreationState getOrCreateScheduleCreationState(long userId) {
        return scheduleCreationStates.computeIfAbsent(userId, k -> new ScheduleCreationState());
    }
    public void setScheduleCreationState(long userId, ScheduleCreationState state) {
        scheduleCreationStates.put(userId, state);
    }
    public void clearScheduleCreationState(long userId) {
        scheduleCreationStates.remove(userId);
    }

    // Clear all states for a user
    public void clearAllUserStates(long userId) {
        clearUserState(userId);
        clearAnalysisInputState(userId);
        clearCredentialsInputState(userId);
        // userQueryContextHistoryId is usually kept unless explicitly cleared by a command
        // lastGeneratedSql is usually cleared after use or by new /query
        clearScheduleCreationState(userId);
    }

    public void clearAllCommandInitiatedStates(long userId) {
        clearUserState(userId);
        clearAnalysisInputState(userId);
        clearCredentialsInputState(userId);
        clearScheduleCreationState(userId);
        // userQueryContextHistoryId is usually cleared by commands like /start, /help, /list_schemas but not by /query itself
        // For now, let commands decide if userQueryContextHistoryId needs clearing.
    }
}