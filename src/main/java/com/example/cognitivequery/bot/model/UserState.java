package com.example.cognitivequery.bot.model;

public enum UserState {
    IDLE,
    WAITING_FOR_REPO_URL,
    WAITING_FOR_LLM_QUERY,
    WAITING_FOR_REPO_URL_FOR_CREDS,
    WAITING_FOR_DB_HOST,
    WAITING_FOR_DB_PORT,
    WAITING_FOR_DB_NAME,
    WAITING_FOR_DB_USER,
    WAITING_FOR_DB_PASSWORD,
    WAITING_FOR_SCHEDULE_NAME,
    WAITING_FOR_SCHEDULE_HISTORY_CHOICE, // If giving choice from list
    WAITING_FOR_SCHEDULE_HISTORY_ID,     // If asking for ID
    WAITING_FOR_SCHEDULE_SQL,
    WAITING_FOR_SCHEDULE_CRON,
    WAITING_FOR_SCHEDULE_CHAT_ID,
    WAITING_FOR_SCHEDULE_OUTPUT_FORMAT;

    public boolean isCredentialInputState() {
        return this == WAITING_FOR_DB_HOST || this == WAITING_FOR_DB_PORT ||
                this == WAITING_FOR_DB_NAME || this == WAITING_FOR_DB_USER ||
                this == WAITING_FOR_DB_PASSWORD;
    }

    public boolean isScheduleCreationState() {
        return this == WAITING_FOR_SCHEDULE_NAME || this == WAITING_FOR_SCHEDULE_HISTORY_CHOICE ||
                this == WAITING_FOR_SCHEDULE_HISTORY_ID || this == WAITING_FOR_SCHEDULE_SQL ||
                this == WAITING_FOR_SCHEDULE_CRON || this == WAITING_FOR_SCHEDULE_CHAT_ID ||
                this == WAITING_FOR_SCHEDULE_OUTPUT_FORMAT;
    }
}