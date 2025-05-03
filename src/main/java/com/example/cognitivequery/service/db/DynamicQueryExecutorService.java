package com.example.cognitivequery.service.db;

import com.example.cognitivequery.service.security.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.*;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class DynamicQueryExecutorService {

    private final EncryptionService encryptionService;

    // Max rows to fetch for SELECT statements to prevent large results
    public static final int MAX_SELECT_ROWS = 50;
    // Query execution timeout in seconds
    private static final int QUERY_TIMEOUT_SECONDS = 30;

    public enum QueryType {SELECT, UPDATE, UNKNOWN}

    // Record to hold the result of the query execution
    public record QueryResult(QueryType type, Object data, String errorMessage) {
        public static QueryResult successSelect(List<Map<String, Object>> rows) {
            return new QueryResult(QueryType.SELECT, rows, null);
        }

        public static QueryResult successUpdate(int rowsAffected) {
            return new QueryResult(QueryType.UPDATE, rowsAffected, null);
        }

        public static QueryResult error(String message) {
            return new QueryResult(QueryType.UNKNOWN, null, message);
        }

        public boolean isSuccess() {
            return errorMessage == null;
        }
    }

    /**
     * Executes a given SQL query against a specified database using provided credentials.
     * Performs basic checks against potentially harmful DDL statements.
     */
    public QueryResult executeQuery(String host, Integer port, String dbName, String username,
                                    String encryptedPassword, String sqlQuery) {

        // Validate inputs
        if (!StringUtils.hasText(host) || port == null || !StringUtils.hasText(dbName) ||
                !StringUtils.hasText(username) || !StringUtils.hasText(encryptedPassword)) {
            log.warn("Attempt to execute query with missing connection details.");
            return QueryResult.error("Missing database connection details.");
        }
        if (!StringUtils.hasText(sqlQuery)) {
            return QueryResult.error("SQL query cannot be empty.");
        }

        // Basic DDL/dangerous command check (can be improved)
        String upperQuery = sqlQuery.trim().toUpperCase();
        if (upperQuery.startsWith("DROP ") || upperQuery.startsWith("TRUNCATE ") ||
                upperQuery.startsWith("ALTER ") || upperQuery.startsWith("DELETE ") && !upperQuery.contains("WHERE")) { // Basic check for DELETE without WHERE
            log.warn("Attempt to execute potentially dangerous/unrestricted statement: {}", sqlQuery.split("\\s+")[0]);
            return QueryResult.error("Execution of potentially dangerous or unrestricted statements (DROP, TRUNCATE, ALTER, DELETE without WHERE) is not allowed.");
        }

        // Decrypt password
        Optional<String> passwordOpt = encryptionService.decrypt(encryptedPassword);
        if (passwordOpt.isEmpty()) {
            log.error("Failed to decrypt database password for user: {}", username); // Avoid logging password hash
            return QueryResult.error("Failed to decrypt database password.");
        }
        String password = passwordOpt.get();

        // Build JDBC URL (ensure driver is loaded - Spring Boot usually handles this)
        String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s", host, port, dbName);
        log.info("Attempting to execute query for user {} on: {}/{}", username, host, dbName);

        // Use try-with-resources for JDBC resources
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            connection.setAutoCommit(true); // Use auto-commit for simplicity unless transactions needed
            log.debug("Database connection established successfully for user {}.", username);

            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS); // Set query timeout

                boolean isSelect = statement.execute(sqlQuery); // Returns true if the first result is a ResultSet

                if (isSelect) {
                    // Process SELECT result
                    log.info("Executing SELECT query for user {}...", username);
                    List<Map<String, Object>> results = new ArrayList<>();
                    try (ResultSet resultSet = statement.getResultSet()) {
                        ResultSetMetaData metaData = resultSet.getMetaData();
                        int columnCount = metaData.getColumnCount();
                        int rowCount = 0;
                        while (resultSet.next() && rowCount < MAX_SELECT_ROWS) {
                            Map<String, Object> row = new LinkedHashMap<>(); // Preserve column order
                            for (int i = 1; i <= columnCount; i++) {
                                String columnName = metaData.getColumnLabel(i); // Use label (alias)
                                Object value = resultSet.getObject(i);
                                row.put(columnName, value); // Store raw object
                            }
                            results.add(row);
                            rowCount++;
                        }
                        log.info("SELECT query executed for user {}. Fetched {} rows (limited to {}).", username, rowCount, MAX_SELECT_ROWS);
                        if (rowCount >= MAX_SELECT_ROWS) {
                            log.warn("Result set for user {} possibly truncated at {} rows.", username, MAX_SELECT_ROWS);
                        }
                    }
                    return QueryResult.successSelect(results);
                } else {
                    // Process INSERT/UPDATE/DELETE result
                    int rowsAffected = statement.getUpdateCount();
                    log.info("Executed UPDATE/INSERT/DELETE query for user {}. Rows affected: {}", username, rowsAffected);
                    return QueryResult.successUpdate(rowsAffected);
                }
            }

        } catch (SQLTimeoutException e) {
            log.error("SQL query timeout ({}) for user {}. Query: '{}'", QUERY_TIMEOUT_SECONDS, username, sqlQuery, e);
            return QueryResult.error(String.format("Query execution timed out after %d seconds.", QUERY_TIMEOUT_SECONDS));
        } catch (SQLException e) {
            log.error("SQL execution failed for user {}. Query: '{}'", username, sqlQuery, e);
            return QueryResult.error("SQL Error: " + e.getSQLState() + " - " + e.getMessage()); // Provide SQLState
        } catch (Exception e) {
            // Catch other potential errors (e.g., ClassNotFoundException if driver missing)
            log.error("Failed to connect to or execute query on user DB for user {}.", username, e);
            return QueryResult.error("Failed to connect/execute: " + e.getMessage());
        }
    }
}