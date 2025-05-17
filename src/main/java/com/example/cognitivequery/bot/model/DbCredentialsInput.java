package com.example.cognitivequery.bot.model;

import com.example.cognitivequery.model.AnalysisHistory;
import lombok.Data;

@Data
public class DbCredentialsInput {
    private String host;
    private Integer port;
    private String name;
    private String username;
    private String encryptedPassword;
    private String associatedRepoUrl; // Used when setting creds directly for an existing repo

    public static DbCredentialsInput fromHistory(AnalysisHistory h) {
        if (!h.hasCredentials()) return null;
        DbCredentialsInput i = new DbCredentialsInput();
        i.host = h.getDbHost();
        i.port = h.getDbPort();
        i.name = h.getDbName();
        i.username = h.getDbUser();
        i.encryptedPassword = h.getDbPasswordEncrypted();
        return i;
    }
}