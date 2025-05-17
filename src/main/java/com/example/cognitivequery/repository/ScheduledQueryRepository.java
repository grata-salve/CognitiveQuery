package com.example.cognitivequery.repository;

import com.example.cognitivequery.model.AppUser;
import com.example.cognitivequery.model.ScheduledQuery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ScheduledQueryRepository extends JpaRepository<ScheduledQuery, Long> {

    List<ScheduledQuery> findByAppUserAndIsEnabledTrueOrderByNextExecutionAtAsc(AppUser appUser);

    // This query implicitly uses FetchType.EAGER on AnalysisHistory due to the entity definition change.
    // If AnalysisHistory were LAZY, we would need a JOIN FETCH here for processScheduledQueries.
    List<ScheduledQuery> findAllByIsEnabledTrueAndNextExecutionAtBeforeOrNextExecutionAtEquals(LocalDateTime now, LocalDateTime nowEquals);

    // Use this for listing to ensure history is fetched if it was LAZY by default.
    // Since AnalysisHistory is now EAGER in ScheduledQuery, this specific JOIN FETCH might be redundant
    // but doesn't harm and is good practice if relations are generally LAZY.
    @Query("SELECT sq FROM ScheduledQuery sq JOIN FETCH sq.analysisHistory WHERE sq.appUser = :appUser ORDER BY sq.createdAt DESC")
    List<ScheduledQuery> findByAppUserWithHistoryOrderByCreatedAtDesc(@Param("appUser") AppUser appUser);
}