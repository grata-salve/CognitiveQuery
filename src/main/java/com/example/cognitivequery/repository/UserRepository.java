package com.example.cognitivequery.repository;

import com.example.cognitivequery.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByGithubId(String githubId);

    Optional<AppUser> findByTelegramId(String telegramId);
}