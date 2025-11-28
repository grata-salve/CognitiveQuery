package com.example.cognitivequery.service.llm;

import com.example.cognitivequery.model.ir.*;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper; // For parsing the JSON schema

    @Value("${google.gemini.api.key}")
    private String apiKey;

    @Value("${google.gemini.api.model}")
    private String modelName;

    @Value("${google.gemini.api.base-url}")
    private String apiBaseUrl;

    private static final ParameterizedTypeReference<Map<String, Object>> GEMINI_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    /**
     * Generates SQL considering the previous context (conversational mode) and text schema.
     */
    public Optional<String> generateSqlFromSchema(String dbSchemaJson, String userQuery, String previousSql) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("Gemini API Key is not configured!");
            return Optional.empty();
        }

        // STEP 1: Convert complex JSON into a clear DDL-like text format, including ENUM values
        String simplifiedSchema = convertSchemaToText(dbSchemaJson);

        String prompt = buildPrompt(simplifiedSchema, userQuery, previousSql);
        Map<String, Object> requestBody = buildRequestBody(prompt);

        return sendRequestToGemini(requestBody);
    }

    public Optional<String> generateSqlFromSchema(String dbSchemaJson, String userQuery) {
        return generateSqlFromSchema(dbSchemaJson, userQuery, null);
    }

    /**
     * Fixes an SQL query based on the database error message.
     */
    public Optional<String> fixSql(String dbSchemaJson, String badSql, String errorMessage) {
        String simplifiedSchema = convertSchemaToText(dbSchemaJson);

        String prompt = String.format(
                """
                You are a PostgreSQL expert.
                
                Database Schema:
                %s
                
                Bad SQL Query:
                ```sql
                %s
                ```
                Error Message: "%s"
                
                Task: Correct the SQL query to fix the error.
                
                CRITICAL INSTRUCTIONS:
                1. Check if the table/column mentioned in the error actually exists in the Schema.
                2. If the entity does NOT exist, return exactly: ABORT_EXPLAIN
                3. If it exists (e.g. typo or enum issue), FIX the SQL.
                4. Return ONLY the raw SQL or the word ABORT_EXPLAIN.
                """,
                simplifiedSchema, badSql, errorMessage
        );

        return sendRequestToGemini(buildRequestBody(prompt));
    }

    /**
     * Explains the reason for the error to the user (when the fix failed).
     */
    public Optional<String> explainError(String dbSchemaJson, String badSql, String errorMessage) {
        String simplifiedSchema = convertSchemaToText(dbSchemaJson);

        String prompt = String.format(
                """
                You are a helpful Data Analyst.
                
                Database Schema:
                %s
                
                Failed SQL:
                ```sql
                %s
                ```
                Error: "%s"
                
                Task: Explain to the user WHY this failed in simple language (2-3 sentences).
                Tell the user what actually exists in the schema if they asked for something missing.
                """,
                simplifiedSchema, badSql, errorMessage
        );

        return sendRequestToGemini(buildRequestBody(prompt));
    }

    /**
     * Analyzes the data and provides a brief insight (for interactive queries).
     */
    public Optional<String> analyzeQueryResult(String userQuery, String sql, String dataJson) {
        String truncatedData = dataJson.length() > 2000 ? dataJson.substring(0, 2000) + "...(truncated)" : dataJson;

        String prompt = String.format(
                """
                You are a Business Intelligence Analyst.
                User Question: "%s"
                Executed SQL: "%s"
                Result Data:
                ```json
                %s
                ```
                Task: Provide a SHORT insight or summary based on this data (2-3 sentences).
                Focus on the answer/value. Use emoji if appropriate.
                """,
                userQuery, sql, truncatedData
        );

        return sendRequestToGemini(buildRequestBody(prompt));
    }

    /**
     * Generates an analytical report for scheduled tasks (period comparison).
     */
    public Optional<String> analyzeReportData(String reportName, String sql, String dataJson) {
        String truncatedData = dataJson.length() > 2000 ? dataJson.substring(0, 2000) + "..." : dataJson;

        String prompt = String.format("""
        You are generating a recurring report named "%s"
        Executed SQL: "%s"
        Data:
        ```json
        %s
        ```
        Task: Write a short executive summary (3-4 sentences).
        If comparing two periods, calculate growth/decline %%.
        Highlight trends. Use emoji (📈, 📉).
        """, reportName, sql, truncatedData);

        return sendRequestToGemini(buildRequestBody(prompt));
    }

    /**
     * Audio Transcription (Voice-to-Text).
     */
    public Optional<String> transcribeAudio(byte[] audioBytes) {
        String base64Audio = java.util.Base64.getEncoder().encodeToString(audioBytes);
        String prompt = "Listen to this audio and transcribe it exactly into text. Return ONLY the text.";

        Map<String, Object> inlineData = Map.of("mime_type", "audio/ogg", "data", base64Audio);
        Map<String, Object> audioPart = Map.of("inline_data", inlineData);
        Map<String, Object> textPart = Map.of("text", prompt);

        Map<String, Object> content = Map.of("parts", List.of(audioPart, textPart));
        Map<String, Object> requestBody = Map.of("contents", List.of(content));

        return sendRequestToGemini(requestBody);
    }

    /**
     * Intent Classification.
     */
    public String determineIntent(String userText) {
        String prompt = String.format("""
            Classify the user's input into one of these COMMANDS:
            1. ANALYZE_REPO - if input is a GitHub URL.
            2. SHOW_SCHEMA - if user asks to visualize schema/diagram.
            3. SETTINGS - if user wants to change settings/options.
            4. QUERY - if user asks about data.
            5. UNKNOWN - gibberish.
            
            User Input: "%s"
            Return ONLY the COMMAND word.
            """, userText);

        return sendRequestToGemini(buildRequestBody(prompt)).orElse("QUERY").trim();
    }

    /**
     * Extracts the settings action from the text.
     */
    public String extractSettingsAction(String userText) {
        String prompt = String.format("""
            Analyze settings request. Input: "%s"
            
            Settings:
            1. VIZ (Charts)
            2. AI (Insights)
            3. MOD (Data Modification)
            4. SHOW_SQL (Show code)
            5. DRY_RUN (No execution)
            
            Response format: "KEY=VALUE" (e.g., VIZ=false, AI=true) or "SHOW_MENU".
            Return ONLY the string.
            """, userText);

        return sendRequestToGemini(buildRequestBody(prompt)).orElse("SHOW_MENU").trim();
    }

    /**
     * Converts JSON schema to a detailed DDL-like text description.
     */
    private String convertSchemaToText(String jsonSchema) {
        try {
            SchemaInfo schema = objectMapper.readValue(jsonSchema, SchemaInfo.class);
            StringBuilder sb = new StringBuilder();

            for (EntityInfo entity : schema.getEntities()) {
                sb.append("Table: ").append(entity.getTableName()).append("\n");

                for (ColumnInfo col : entity.getColumns()) {
                    sb.append("  - ").append(col.getColumnName());

                    // 1. DATA TYPE + LENGTH/PRECISION
                    sb.append(" (").append(col.getSqlType());
                    if ("VARCHAR".equalsIgnoreCase(col.getSqlType()) && col.getLength() != null && col.getLength() > 0) {
                        sb.append("(").append(col.getLength()).append(")");
                    } else if (("DECIMAL".equalsIgnoreCase(col.getSqlType()) || "NUMERIC".equalsIgnoreCase(col.getSqlType()))
                               && col.getPrecision() != null && col.getScale() != null) {
                        sb.append("(").append(col.getPrecision()).append(",").append(col.getScale()).append(")");
                    }
                    sb.append(")");

                    // 2. PRIMARY KEY + AUTO INCREMENT
                    if (col.isPrimaryKey()) {
                        sb.append(" [PK]");
                        // Hint that ID is auto-generated
                        if ("IDENTITY".equals(col.getGenerationStrategy()) || "SEQUENCE".equals(col.getGenerationStrategy())) {
                            sb.append(" [AUTO_INCREMENT]");
                        }
                    }

                    // 3. NULLABILITY (NOT NULL)
                    // If explicitly set to nullable=false and not PK (PK is already not null)
                    if (Boolean.FALSE.equals(col.getNullable()) && !col.isPrimaryKey()) {
                        sb.append(" [NOT NULL]");
                    }

                    // 4. UNIQUENESS
                    if (Boolean.TRUE.equals(col.getUnique()) && !col.isPrimaryKey()) {
                        sb.append(" [UNIQUE]");
                    }

                    // 5. ENUM HANDLING
                    if (Boolean.TRUE.equals(col.getIsEnum()) && col.getEnumInfo() != null) {
                        EnumInfo en = col.getEnumInfo();
                        sb.append(" -- ENUM VALUES: ");
                        List<String> values = en.getPossibleValues();

                        if (values == null || values.isEmpty()) {
                            sb.append("(UNKNOWN)");
                        } else if ("ORDINAL".equalsIgnoreCase(en.getStorageType())) {
                            sb.append("[");
                            for (int i = 0; i < values.size(); i++) {
                                sb.append(i).append("='").append(values.get(i)).append("'");
                                if (i < values.size() - 1) sb.append(", ");
                            }
                            sb.append("]");
                        } else {
                            sb.append(values);
                        }
                    }
                    sb.append("\n");
                }

                // 6. FOREIGN KEYS (FK) from relationships
                if (entity.getRelationships() != null) {
                    for (RelationshipInfo rel : entity.getRelationships()) {
                        if (rel.isOwningSide() && rel.getJoinColumnName() != null) {
                            // Check for duplicates
                            boolean alreadyListed = entity.getColumns().stream()
                                    .anyMatch(c -> c.getColumnName().equals(rel.getJoinColumnName()));

                            if (!alreadyListed) {
                                String targetClass = rel.getTargetEntityJavaClass();
                                String simpleTarget = targetClass.substring(targetClass.lastIndexOf('.') + 1);

                                sb.append("  - ").append(rel.getJoinColumnName())
                                        .append(" (BIGINT) [FK] -- References Table: ").append(simpleTarget) // Hint the target table
                                        .append("\n");
                            }
                        }
                    }
                }

                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to simplify schema", e);
            return jsonSchema;
        }
    }

    private String buildPrompt(String schemaText, String userQuery, String previousSql) {
        String historyPart = "";
        if (previousSql != null && !previousSql.isBlank()) {
            historyPart = String.format("""
                    PREVIOUS SQL CONTEXT:
                    ```sql
                    %s
                    ```
                    (Modify this if the user asks for a refinement)
                    """, previousSql);
        }

        return String.format(
                """
                Role: PostgreSQL Expert.
                
                Database Schema Definition:
                ```text
                %s
                ```
                
                %s
                User Request: "%s"
                
                Instructions:
                1. Use the Schema Definition. Pay close attention to ENUM VALUES comments.
                   - If column is INTEGER but has ENUM [0='A', 1='B'], use `WHERE col = 1` for 'B'.
                2. If comparing periods, use UNION ALL for chart-friendly output.
                3. If data missing -> NO_DATA: <reason>
                4. Return ONLY raw SQL.
                """,
                schemaText,
                historyPart,
                userQuery
        );
    }

    /**
     * Checks if the alert condition is met based on the data.
     * Returns true if a notification should be sent.
     */
    public boolean checkAlertCondition(String condition, String dataJson) {
        String prompt = String.format(
                """
                Role: Data Monitor.
                
                Data (JSON):
                ```json
                %s
                ```
                
                Alert Condition (User defined): "%s"
                
                Task: Evaluate if the Data meets the Alert Condition.
                - If the condition is met (TRUE), return "TRUE".
                - If not met (FALSE), return "FALSE".
                - If data is empty and condition implies checking value, return "FALSE".
                
                Examples:
                Data: [{"count": 5}], Condition: "count > 10" -> FALSE
                Data: [{"status": "ERROR"}], Condition: "if any status is ERROR" -> TRUE
                
                Response: ONLY "TRUE" or "FALSE".
                """,
                dataJson, condition
        );

        Optional<String> result = sendRequestToGemini(buildRequestBody(prompt));
        return result.map(s -> s.toUpperCase().contains("TRUE")).orElse(false);
    }

    /**
     * Common sending method with RETRY mechanism.
     */
    private Optional<String> sendRequestToGemini(Map<String, Object> requestBody) {
        WebClient client = webClientBuilder.baseUrl(apiBaseUrl).build();
        String apiUrlPath = String.format("/models/%s:generateContent?key=%s", modelName, apiKey);

        int maxRetries = 3;
        long waitTimeMs = 2000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.debug("Sending request to Gemini API (Attempt {}/{})", attempt, maxRetries);

                Map<String, Object> response = client.post()
                        .uri(apiUrlPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(BodyInserters.fromValue(requestBody))
                        .retrieve()
                        .bodyToMono(GEMINI_RESPONSE_TYPE)
                        .block();

                return extractSqlFromResponse(response);

            } catch (WebClientResponseException e) {
                // If the error is 503 (Overloaded) or 429 (Rate Limit)
                if (e.getStatusCode().value() == 503 || e.getStatusCode().value() == 429) {
                    log.warn("⚠️ Gemini API overloaded ({}). Retrying in {}ms...", e.getStatusCode(), waitTimeMs);
                    if (attempt == maxRetries) {
                        log.error("❌ Gemini API failed after {} attempts.", maxRetries);
                        return Optional.empty();
                    }
                    try {
                        Thread.sleep(waitTimeMs);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return Optional.empty();
                    }
                    waitTimeMs *= 2; // Exponential backoff
                } else {
                    log.error("Gemini API Fatal Error: {}", e.getResponseBodyAsString());
                    return Optional.empty();
                }
            } catch (Exception e) {
                log.error("Unexpected error calling Gemini API", e);
                return Optional.empty();
            }
        }
        return Optional.empty();
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
            Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
            if (content == null) return Optional.empty();
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            if (parts == null || parts.isEmpty()) return Optional.empty();

            String text = (String) parts.get(0).get("text");
            if (text == null || text.isBlank()) return Optional.empty();

            // 1. Search for a Markdown code block
            int codeStart = text.indexOf("```");
            if (codeStart != -1) {
                int codeEnd = text.indexOf("```", codeStart + 3);
                if (codeEnd != -1) {
                    String codeBlock = text.substring(codeStart + 3, codeEnd).trim();
                    if (codeBlock.toLowerCase().startsWith("sql")) {
                        codeBlock = codeBlock.substring(3).trim();
                    }
                    return Optional.of(codeBlock);
                }
            }

            // 2. If no Markdown block is found, use heuristic (search for keywords at the start of a line)
            String[] keywords = {"SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP", "TRUNCATE", "VALUES"};
            String upper = text.toUpperCase();

            int bestIdx = -1;

            for (String keyword : keywords) {
                int idx = upper.indexOf(keyword);
                while (idx != -1) {
                    boolean isStartOfLine = (idx == 0) || (text.charAt(idx - 1) == '\n') || (text.charAt(idx - 1) == '\r');

                    if (isStartOfLine) {
                        if (bestIdx == -1 || idx < bestIdx) {
                            bestIdx = idx;
                        }
                        break;
                    }
                    idx = upper.indexOf(keyword, idx + 1);
                }
            }

            if (bestIdx != -1) {
                return Optional.of(text.substring(bestIdx).trim());
            }

            // 3. Check for special commands
            if (text.contains("ABORT_EXPLAIN") || text.startsWith("NO_DATA") || text.startsWith("SHOW_MENU")) {
                return Optional.of(text.trim());
            }

            // 4. Fallback: return as is
            return Optional.of(text.trim());

        } catch (Exception e) {
            log.error("Parsing error", e);
        }
        return Optional.empty();
    }
}