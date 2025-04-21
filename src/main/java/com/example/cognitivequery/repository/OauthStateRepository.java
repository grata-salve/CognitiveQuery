package com.example.cognitivequery.repository;

import com.example.cognitivequery.model.OauthState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface OauthStateRepository extends JpaRepository<OauthState, String> {

    Optional<OauthState> findByStateAndExpiresAtAfter(String state, LocalDateTime now);

    void deleteByExpiresAtBefore(LocalDateTime now); // For cleaning up expired states
}