package com.example.cognitivequery.service.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class GeminiService {

    private final WebClient.Builder webClientBuilder;

    @Value("${google.gemini.api.key}")
    private String apiKey;

    @Value("${google.gemini.api.model}")
    private String modelName;

    @Value("${google.gemini.api.base-url}")
    private String apiBaseUrl;

    private static final ParameterizedTypeReference<Map<String, Object>> GEMINI_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    /**
     * Generates SQL considering the previous context (conversational mode).
     */
    public Optional<String> generateSqlFromSchema(String dbSchemaJson, String userQuery, String previousSql) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("Gemini API Key is not configured!");
            return Optional.empty();
        }

        String prompt = buildPrompt(dbSchemaJson, userQuery, previousSql);
        Map<String, Object> requestBody = buildRequestBody(prompt);

        return sendRequestToGemini(requestBody);
    }

    public Optional<String> generateSqlFromSchema(String dbSchemaJson, String userQuery) {
        return generateSqlFromSchema(dbSchemaJson, userQuery, null);
    }

    private String buildPrompt(String dbSchemaJson, String userQuery, String previousSql) {
        String historyPart = "";
        if (previousSql != null && !previousSql.isBlank()) {
            historyPart = String.format("""
                    
                    PREVIOUSLY EXECUTED SQL:
                    ```sql
                    %s
                    ```
                    
                    INSTRUCTION FOR REFINEMENT:
                    1. IF the new request is a follow-up, MODIFY the Previous SQL.
                    2. IF the new request is a new topic, IGNORE Previous SQL.
                    """, previousSql);
        }

        return String.format(
                """
                You are an AI assistant specialized in generating SQL queries for PostgreSQL.
                
                Database Schema:
                ```json
                %s
                ```
                %s
                User Request: "%s"
                
                CRITICAL INSTRUCTIONS:
                1. Analyze the Schema. Does it contain the data requested by the user?
                2. IF YES -> Generate a PostgreSQL query. Respond ONLY with the raw SQL (no markdown).
                3. IF NO (e.g., user asks for "cars" but no car tables exist) -> Respond exactly with: NO_DATA: <Short explanation why>
                
                Examples:
                - User: "Show users" -> SELECT * FROM users;
                - User: "Show weather" (and no weather table) -> NO_DATA: The database schema does not contain weather information.
                """,
                dbSchemaJson,
                historyPart,
                userQuery
        );
    }

    /**
     * Fixes an SQL query based on the database error message.
     */
    public Optional<String> fixSql(String dbSchemaJson, String badSql, String errorMessage) {
        String prompt = String.format(
                """
                You are a PostgreSQL expert. I tried to execute a query you generated, but it failed.
                
                Database Schema:
                ```json
                %s
                ```
                
                Bad SQL Query:
                ```sql
                %s
                ```
                
                Error Message from Database:
                "%s"
                
                Task: Correct the SQL query to fix the error.
                
                CRITICAL INSTRUCTIONS:
                1. Check if the table or column mentioned in the error actually exists in the Schema.
                2. If it exists (maybe a typo like "user" vs "users"), FIX the SQL and return ONLY the raw SQL.
                3. If the table/column does NOT exist in the schema at all, DO NOT generate a fake query. Instead, return exactly the word: ABORT_EXPLAIN
                
                Respond ONLY with the SQL or the word ABORT_EXPLAIN.
                """,
                dbSchemaJson, badSql, errorMessage
        );

        Map<String, Object> requestBody = buildRequestBody(prompt);
        return sendRequestToGemini(requestBody);
    }

    /**
     * Explains the query failure to the user in simple terms.
     */
    public Optional<String> explainError(String dbSchemaJson, String badSql, String errorMessage) {
        String prompt = String.format(
                """
                You are a helpful Data Analyst. I tried to execute a SQL query, but it failed.
                
                Database Schema:
                ```json
                %s
                ```
                
                Failed SQL:
                ```sql
                %s
                ```
                
                Error Message:
                "%s"
                
                Task: Explain to the user WHY this failed in simple, concise language (2-3 sentences).
                If the error is about missing columns/tables, assume the Schema is the source of truth and tell the user what actually exists.
                Do NOT suggest new SQL code, just explain the problem.
                """,
                dbSchemaJson, badSql, errorMessage
        );

        Map<String, Object> requestBody = buildRequestBody(prompt);
        return sendRequestToGemini(requestBody);
    }

    /**
     * Generates a short analytical summary based on the query results.
     */
    public Optional<String> analyzeQueryResult(String userQuery, String sql, String dataJson) {
        // Truncate data to avoid exceeding context window
        String truncatedData = dataJson.length() > 2000 ? dataJson.substring(0, 2000) + "...(truncated)" : dataJson;

        String prompt = String.format(
                """
                You are a Business Intelligence Analyst.
                
                User Question: "%s"
                Executed SQL: "%s"
                
                Result Data (JSON):
                ```json
                %s
                ```
                
                Task: Provide a SHORT, concise insight or summary based on this data. 
                Answer the user's question directly using the numbers. 
                Do NOT describe the table structure. Focus on the value/answer.
                If the data is empty, just say "No data found matching criteria".
                Max length: 2-3 sentences.
                """,
                userQuery, sql, truncatedData
        );

        Map<String, Object> requestBody = buildRequestBody(prompt);
        return sendRequestToGemini(requestBody);
    }

    /**
     * Common method for sending requests to the Gemini API.
     */
    private Optional<String> sendRequestToGemini(Map<String, Object> requestBody) {
        WebClient client = webClientBuilder.baseUrl(apiBaseUrl).build();
        String apiUrlPath = String.format("/models/%s:generateContent?key=%s", modelName, apiKey);

        log.debug("Sending request to Gemini API...");

        try {
            Map<String, Object> response = client.post()
                    .uri(apiUrlPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(requestBody))
                    .retrieve()
                    .bodyToMono(GEMINI_RESPONSE_TYPE)
                    .block();

            return extractSqlFromResponse(response);

        } catch (WebClientResponseException e) {
            log.error("Gemini API Error: Status Code: {}, Response Body: {}", e.getStatusCode(), e.getResponseBodyAsString(), e);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Unexpected error calling Gemini API", e);
            return Optional.empty();
        }
    }

    private Map<String, Object> buildRequestBody(String prompt) {
        Map<String, String> textPart = Map.of("text", prompt);
        Map<String, List<Map<String, String>>> content = Map.of("parts", List.of(textPart));
        return Map.of("contents", List.of(content));
    }

    @SuppressWarnings("unchecked")
    private Optional<String> extractSqlFromResponse(Map<String, Object> response) {
        if (response == null) return Optional.empty();
        try {
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
            if (candidates == null || candidates.isEmpty()) return Optional.empty();

            Map<String, Object> candidate = candidates.getFirst();
            Map<String, Object> content = (Map<String, Object>) candidate.get("content");
            if (content == null) return Optional.empty();

            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            if (parts == null || parts.isEmpty()) return Optional.empty();

            String generatedText = (String) parts.getFirst().get("text");
            if (generatedText != null && !generatedText.isBlank()) {
                String cleanedSql = generatedText.trim().replace("```sql", "").replace("```", "").trim();
                return Optional.of(cleanedSql);
            }
        } catch (Exception e) {
            log.error("Error parsing Gemini response", e);
        }
        return Optional.empty();
    }
}