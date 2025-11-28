package com.example.cognitivequery.bot.model;

import com.example.cognitivequery.model.AnalysisHistory;
import lombok.Data;

@Data
public class ScheduleCreationState {
    private String name;
    private Long analysisHistoryId;
    private AnalysisHistory analysisHistory; // For quick access after selection
    private String sqlQuery;
    private String cronExpression;
    private Long chatIdToNotify;
    private String outputFormat;
    private String alertCondition;
}