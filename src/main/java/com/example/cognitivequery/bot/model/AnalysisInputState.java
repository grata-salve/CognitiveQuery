package com.example.cognitivequery.bot.model;

import lombok.Data;

@Data
public class AnalysisInputState {
    private String repoUrl;
    private String commitHash;
    private DbCredentialsInput credentials;
}