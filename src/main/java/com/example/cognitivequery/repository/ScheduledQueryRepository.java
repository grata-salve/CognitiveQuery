package com.example.cognitivequery.repository;

import com.example.cognitivequery.model.AppUser;
import com.example.cognitivequery.model.ScheduledQuery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ScheduledQueryRepository extends JpaRepository<ScheduledQuery, Long> {

    List<ScheduledQuery> findByAppUserAndIsEnabledTrueOrderByNextExecutionAtAsc(AppUser appUser);

    List<ScheduledQuery> findAllByIsEnabledTrueAndNextExecutionAtBeforeOrNextExecutionAtEquals(LocalDateTime now, LocalDateTime nowEquals);

    List<ScheduledQuery> findByAppUserOrderByCreatedAtDesc(AppUser appUser);
}