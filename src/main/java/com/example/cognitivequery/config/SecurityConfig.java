package com.example.cognitivequery.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authz -> authz
                        // Allow access to the OAuth initiation endpoint and the callback
                        .requestMatchers(HttpMethod.POST, "/api/auth/github/initiate").permitAll()
                        .requestMatchers(HttpMethod.GET, "/login/oauth2/code/github").permitAll()
                        // .requestMatchers("/oauth_success", "/oauth_error", "/oauth_error_github_linked").permitAll()
                        .anyRequest().permitAll() // Or .anyRequest().authenticated() if you have other secured APIs
                );

        return http.build();
    }
}