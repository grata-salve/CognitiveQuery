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

    // TypeReference for parsing Gemini API response
    private static final ParameterizedTypeReference<Map<String, Object>> GEMINI_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    /**
     * Generates an SQL query based on a database schema and a user's natural language query.
     *
     * @param dbSchemaJson The database schema description as a JSON string.
     * @param userQuery    The user's query in natural language.
     * @return Optional containing the generated SQL query string, or Optional.empty() if an error occurs.
     */
    public Optional<String> generateSqlFromSchema(String dbSchemaJson, String userQuery) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("Gemini API Key is not configured!");
            return Optional.empty();
        }

        String prompt = buildPrompt(dbSchemaJson, userQuery);
        Map<String, Object> requestBody = buildRequestBody(prompt);

        WebClient client = webClientBuilder.baseUrl(apiBaseUrl).build();
        // Construct the API URL path correctly relative to the base URL
        String apiUrlPath = String.format("/models/%s:generateContent?key=%s", modelName, apiKey);

        log.debug("Sending request to Gemini API. URL Path: {}...", apiUrlPath.substring(0, apiUrlPath.indexOf('?'))); // Avoid logging the key

        try {
            // Perform the POST request
            Map<String, Object> response = client.post()
                    .uri(apiUrlPath) // Use the path relative to the base URL
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(requestBody))
                    .retrieve()
                    .bodyToMono(GEMINI_RESPONSE_TYPE)
                    .block(); // Using block() for simplicity; consider reactive streams for production

            return extractSqlFromResponse(response);

        } catch (WebClientResponseException e) {
            // Handle specific API errors (4xx, 5xx)
            log.error("Gemini API Error: Status Code: {}, Response Body: {}", e.getStatusCode(), e.getResponseBodyAsString(), e);
            return Optional.empty();
        } catch (Exception e) {
            // Handle other unexpected errors during the API call
            log.error("Unexpected error calling Gemini API", e);
            return Optional.empty();
        }
    }

    /**
     * Constructs the prompt for the Gemini model.
     */
    private String buildPrompt(String dbSchemaJson, String userQuery) {
        // This prompt can be further refined with examples (few-shot learning) for better results
        return String.format(
                """
                        You are an AI assistant specialized in generating SQL queries for PostgreSQL based on a provided database schema and a user request.
                        Analyze the following database schema provided in JSON format:
                        
                        ```json
                        %s
                        ```
                        
                        Based on this schema, generate a PostgreSQL SQL query that fulfills the following user request:
                        User Request: "%s"
                        
                        IMPORTANT: Respond ONLY with the raw SQL query, without any explanations, comments, or markdown formatting like ```sql ... ```.""",
                dbSchemaJson,
                userQuery
        );
    }

    /**
     * Creates the request body payload for the Gemini API.
     */
    private Map<String, Object> buildRequestBody(String prompt) {
        // Structure according to Gemini API v1beta documentation
        Map<String, String> textPart = Map.of("text", prompt);
        Map<String, List<Map<String, String>>> content = Map.of("parts", List.of(textPart));
        // generationConfig or safetySettings can be added here if needed
        return Map.of("contents", List.of(content));
    }

    /**
     * Extracts the generated text (expected SQL) from the Gemini API response structure.
     */
    @SuppressWarnings("unchecked") // Suppress warnings for casting generic Map/List from response
    private Optional<String> extractSqlFromResponse(Map<String, Object> response) {
        if (response == null) {
            log.warn("Received null response from Gemini API.");
            return Optional.empty();
        }

        try {
            // Navigate the Gemini response structure
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
            if (candidates == null || candidates.isEmpty()) {
                log.warn("No 'candidates' found in Gemini response: {}", response);
                // Check for content blocking
                Map<String, Object> promptFeedback = (Map<String, Object>) response.get("promptFeedback");
                if (promptFeedback != null && "BLOCK".equals(promptFeedback.get("blockReason"))) {
                    log.error("Gemini API blocked the prompt. Reason: {}", promptFeedback.get("blockReason"));
                }
                return Optional.empty();
            }

            Map<String, Object> candidate = candidates.getFirst(); // Get the first candidate
            Map<String, Object> content = (Map<String, Object>) candidate.get("content");
            if (content == null) { log.warn("No 'content' found in Gemini candidate: {}", candidate); return Optional.empty(); }

            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            if (parts == null || parts.isEmpty()) { log.warn("No 'parts' found in Gemini content: {}", content); return Optional.empty(); }

            String generatedText = (String) parts.getFirst().get("text"); // Get text from the first part
            if (generatedText != null && !generatedText.isBlank()) {
                // Clean up potential Markdown formatting if the model added it despite instructions
                String cleanedSql = generatedText.trim().replace("```sql", "").replace("```", "").trim();
                log.info("Successfully extracted SQL from Gemini response.");
                log.debug("Generated SQL: {}", cleanedSql); // Log SQL at DEBUG level
                return Optional.of(cleanedSql);
            } else {
                log.warn("Empty 'text' found in Gemini response part: {}", parts.getFirst());
            }
        } catch (ClassCastException | NullPointerException e) {
            // Handle potential errors if the response structure is not as expected
            log.error("Error parsing Gemini API response structure: {}", response, e);
        }
        return Optional.empty();
    }
}