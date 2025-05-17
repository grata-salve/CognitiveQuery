package com.example.cognitivequery.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "scheduled_queries", indexes = {
        @Index(columnList = "app_user_id"),
        @Index(columnList = "isEnabled, nextExecutionAt")
})
@Getter
@Setter
@NoArgsConstructor
public class ScheduledQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser appUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_history_id", nullable = false)
    private AnalysisHistory analysisHistory; // Ссылка на конкретный анализ (версию схемы)

    @Column(nullable = false, columnDefinition = "TEXT")
    private String sqlQuery;

    @Column(nullable = false, length = 100)
    private String cronExpression;

    @Column(nullable = false) // ID чата, куда слать уведомления/результаты
    private Long chatIdToNotify;

    @Column(nullable = false, length = 10) // "text", "csv", "txt"
    private String outputFormat;

    @Column(length = 255) // Опциональное имя для расписания
    private String name;

    @Column(nullable = false)
    private boolean isEnabled = true;

    private LocalDateTime nextExecutionAt;
    private LocalDateTime lastExecutedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Column(length = 64) // Для хранения часового пояса пользователя, если понадобится
    private String timezoneId; // e.g., "Europe/Moscow"

    public ScheduledQuery(AppUser appUser, AnalysisHistory analysisHistory, String sqlQuery, String cronExpression, Long chatIdToNotify, String outputFormat, String name) {
        this.appUser = appUser;
        this.analysisHistory = analysisHistory;
        this.sqlQuery = sqlQuery;
        this.cronExpression = cronExpression;
        this.chatIdToNotify = chatIdToNotify;
        this.outputFormat = outputFormat;
        this.name = name;
        this.isEnabled = true;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}