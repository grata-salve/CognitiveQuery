package com.example.cognitivequery.bot.handler;

import com.example.cognitivequery.bot.CognitiveQueryTelegramBot; // Assuming this will be the main bot class
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class TelegramMessageHelper {

    private final CognitiveQueryTelegramBot bot; // The bot instance to use its execute method

    private static final int MAX_SELECT_ROWS_FOR_CHAT_TEXT = 20;
    private static final int MAX_COLUMNS_FOR_CHAT_TEXT = 4;

    public TelegramMessageHelper(CognitiveQueryTelegramBot bot) {
        this.bot = bot;
    }

    public void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setParseMode("MarkdownV2");
        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send MarkdownV2 message to chat ID {}: {}. Retrying as plain text.", chatId, e.getMessage(), e);
            message.setParseMode(null); // Try sending as plain text
            try {
                bot.execute(message);
            } catch (TelegramApiException ex) {
                log.error("Failed to send plain text message to chat ID {}: {}", chatId, ex.getMessage(), ex);
            }
        }
    }

    public void tryExecute(SendMessage message) {
        org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard originalKeyboard = message.getReplyMarkup();
        String chatIdStr = message.getChatId();

        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            log.warn("Attempt 1 (original) failed for SendMessage to chat ID {}: {}. Message: '{}'. Trying fallbacks.", chatIdStr, e.getMessage(), message.getText());

            if (originalKeyboard != null) {
                message.setReplyMarkup(null);
                try {
                    bot.execute(message);
                    log.info("Attempt 2 (original parse mode, no keyboard) successful for chat ID {}.", chatIdStr);
                    return;
                } catch (TelegramApiException e2) {
                    log.warn("Attempt 2 (original parse mode, no keyboard) failed for chat ID {}: {}. Message: '{}'", chatIdStr, e2.getMessage(), message.getText());
                }
            }

            message.setParseMode(null);
            message.setReplyMarkup(null);
            try {
                bot.execute(message);
                log.info("Attempt 3 (plain text, no keyboard) successful for chat ID {}.", chatIdStr);
            } catch (TelegramApiException ex) {
                log.error("All SendMessage attempts failed for chat ID {}. Final error: {}. Message: '{}'", chatIdStr, ex.getMessage(), message.getText(), ex);
            }
        }
    }

    public void sendAnswerCallbackQuery(String callbackQueryId, String text, boolean showAlert) {
        AnswerCallbackQuery answer = AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .text(text)
                .showAlert(showAlert)
                .build();
        try {
            bot.execute(answer);
        } catch (TelegramApiException e) {
            log.error("Failed to answer callback query", e);
        }
    }

    public String escapeMarkdownV2(String text) {
        if (text == null) return "";
        // Order matters: escape backslash first, then other characters.
        return text.replace("\\", "\\\\")
                .replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("~", "\\~")
                .replace("`", "\\`")
                .replace(">", "\\>")
                .replace("#", "\\#")
                .replace("+", "\\+")
                .replace("-", "\\-")
                .replace("=", "\\=")
                .replace("|", "\\|")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace(".", "\\.")
                .replace("!", "\\!");
    }


    public void sendSelectResultAsTextInChat(long chatId, List<Map<String, Object>> rows, String repoUrlForFilename) {
        if (rows == null || rows.isEmpty()) {
            sendMessage(chatId, "✅ Query executed successfully, but it returned no rows\\.");
            return;
        }

        List<String> headers = new ArrayList<>(rows.getFirst().keySet());

        if (headers.size() > MAX_COLUMNS_FOR_CHAT_TEXT) {
            sendMessage(chatId, "ℹ️ The result table has too many columns \\(" + headers.size() +
                    "\\) for chat display\\.\nSwitching to TXT file output\\.");
            sendSelectResultAsTxtFile(chatId, rows, repoUrlForFilename);
            return;
        }

        final int MAX_CELL_CONTENT_DISPLAY_LENGTH_CHAT = 30;
        final String TRUNCATION_MARKER = "...";
        final int TRUNCATION_MARKER_LEN = TRUNCATION_MARKER.length();

        Map<String, Integer> columnWidths = new HashMap<>();
        Map<String, String> processedHeaders = new HashMap<>();

        for (String headerName : headers) {
            String displayHeader = headerName;
            if (headerName.length() > MAX_CELL_CONTENT_DISPLAY_LENGTH_CHAT) {
                displayHeader = headerName.substring(0, MAX_CELL_CONTENT_DISPLAY_LENGTH_CHAT - TRUNCATION_MARKER_LEN) + TRUNCATION_MARKER;
            }
            processedHeaders.put(headerName, displayHeader);
            columnWidths.put(headerName, displayHeader.length());
        }

        int dataRowDisplayLimit = Math.min(rows.size(), MAX_SELECT_ROWS_FOR_CHAT_TEXT);
        List<List<String>> processedDataRows = new ArrayList<>();

        for (int i = 0; i < dataRowDisplayLimit; i++) {
            Map<String, Object> row = rows.get(i);
            List<String> processedRow = new ArrayList<>();
            for (String headerName : headers) {
                Object value = row.get(headerName);
                String cellText = (value != null ? value.toString() : "NULL");
                cellText = cellText.replace("\n", " ").replace("\r", " ");

                String displayCellText = cellText;
                if (cellText.length() > MAX_CELL_CONTENT_DISPLAY_LENGTH_CHAT) {
                    displayCellText = cellText.substring(0, MAX_CELL_CONTENT_DISPLAY_LENGTH_CHAT - TRUNCATION_MARKER_LEN) + TRUNCATION_MARKER;
                }
                processedRow.add(displayCellText);
                columnWidths.put(headerName, Math.max(columnWidths.get(headerName), displayCellText.length()));
            }
            processedDataRows.add(processedRow);
        }

        StringBuilder formattedTable = new StringBuilder();
        String columnSeparator = " | ";
        String headerLineSeparatorPart = "-|-";

        for (int i = 0; i < headers.size(); i++) {
            String headerName = headers.get(i);
            String displayHeader = processedHeaders.get(headerName);
            int width = columnWidths.get(headerName);
            formattedTable.append(String.format("%-" + width + "s", displayHeader));
            if (i < headers.size() - 1) formattedTable.append(columnSeparator);
        }
        formattedTable.append("\n");

        for (int i = 0; i < headers.size(); i++) {
            int width = columnWidths.get(headers.get(i));
            formattedTable.append("-".repeat(width));
            if (i < headers.size() - 1) formattedTable.append(headerLineSeparatorPart);
        }
        formattedTable.append("\n");

        for (List<String> rowData : processedDataRows) {
            for (int i = 0; i < headers.size(); i++) {
                String displayCellText = rowData.get(i);
                int width = columnWidths.get(headers.get(i));
                formattedTable.append(String.format("%-" + width + "s", displayCellText));
                if (i < headers.size() - 1) formattedTable.append(columnSeparator);
            }
            formattedTable.append("\n");
        }

        String tableContent = formattedTable.toString().trim();
        String firstLineForCheck = tableContent.lines().findFirst().orElse("");
        int approxTableWidth = firstLineForCheck.length();
        int maxChatTableWidth = 150;

        if (approxTableWidth > maxChatTableWidth && dataRowDisplayLimit > 1) {
            sendMessage(chatId, "ℹ️ The result table is still quite wide for chat display, even with " + headers.size() + " columns\\. Switching to TXT file output\\.");
            sendSelectResultAsTxtFile(chatId, rows, repoUrlForFilename);
            return;
        }

        String messagePrefix = "✅ Query executed successfully\\. Results" +
                (rows.size() > dataRowDisplayLimit ? " \\(showing first " + dataRowDisplayLimit + " of " + rows.size() + " rows\\):" : ":") +
                "\n\n";
        String fullMessage = messagePrefix + "```\n" + tableContent + "\n```";

        int telegramMaxLen = 4096;
        if (fullMessage.length() <= telegramMaxLen) {
            sendMessage(chatId, fullMessage);
        } else {
            sendMessage(chatId, "ℹ️ The result is too long to display directly in chat, even after truncating rows\\. Switching to TXT file output\\.");
            sendSelectResultAsTxtFile(chatId, rows, repoUrlForFilename);
        }
    }

    private String escapeCsvField(String data) {
        if (data == null) return "";
        if (data.contains(",") || data.contains("\"") || data.contains("\n") || data.contains("\r")) {
            return "\"" + data.replace("\"", "\"\"") + "\"";
        }
        return data;
    }

    public void sendSelectResultAsCsvFile(long chatId, List<Map<String, Object>> rows, String repoUrlForFilename) {
        if (rows == null || rows.isEmpty()) {
            sendMessage(chatId, "✅ Query executed successfully, but it returned no rows (nothing to put in CSV)\\.");
            return;
        }
        List<String> headers = new ArrayList<>(rows.getFirst().keySet());
        StringBuilder csvContent = new StringBuilder();
        csvContent.append(headers.stream().map(this::escapeCsvField).collect(Collectors.joining(","))).append("\n");

        for (Map<String, Object> row : rows) {
            csvContent.append(headers.stream()
                            .map(header -> row.get(header)).map(value -> (value != null ? value.toString() : ""))
                            .map(this::escapeCsvField).collect(Collectors.joining(",")))
                    .append("\n");
        }
        Path tempFile = null;
        try {
            String safeRepoPart = "query_results";
            if (repoUrlForFilename != null && !repoUrlForFilename.isBlank()) {
                String[] urlParts = repoUrlForFilename.split("/");
                if (urlParts.length > 0) {
                    safeRepoPart = urlParts[urlParts.length - 1].replaceAll("[^a-zA-Z0-9.\\-_]", "_").replaceAll("\\.git$", "");
                }
            }
            safeRepoPart = safeRepoPart.length() > 30 ? safeRepoPart.substring(0, 30) : safeRepoPart;
            String filename = safeRepoPart + "_" + System.currentTimeMillis() + ".csv";

            tempFile = Files.createTempFile("cq_csv_", ".csv");
            Files.writeString(tempFile, csvContent.toString(), StandardCharsets.UTF_8);

            SendDocument sendDocumentRequest = new SendDocument();
            sendDocumentRequest.setChatId(String.valueOf(chatId));
            sendDocumentRequest.setDocument(new InputFile(tempFile.toFile(), filename));
            sendDocumentRequest.setCaption("✅ Query executed successfully. Results are in the attached CSV file.");
            bot.execute(sendDocumentRequest);
        } catch (IOException | TelegramApiException e) {
            log.error("Failed to send results as CSV file to chat ID {}", chatId, e);
            if (e instanceof IOException) sendMessage(chatId, "❌ Error: Could not generate CSV results file.");
        } finally {
            if (tempFile != null) try { Files.deleteIfExists(tempFile); } catch (IOException e) { log.warn("Failed to delete temp CSV file", e); }
        }
    }

    public void sendSelectResultAsTxtFile(long chatId, List<Map<String, Object>> rows, String repoUrlForFilename) {
        if (rows == null || rows.isEmpty()) {
            sendMessage(chatId, "✅ Query executed successfully, but it returned no rows (nothing to put in TXT file)\\.");
            return;
        }

        final int MAX_CELL_CONTENT_DISPLAY_LENGTH_FILE = 60;
        final String TRUNCATION_MARKER = "...";
        final int TRUNCATION_MARKER_LEN = TRUNCATION_MARKER.length();

        List<String> headers = new ArrayList<>(rows.getFirst().keySet());
        Map<String, Integer> columnWidths = new HashMap<>();
        Map<String, String> processedHeaders = new HashMap<>();

        for (String headerName : headers) {
            String displayHeader = headerName;
            if (headerName.length() > MAX_CELL_CONTENT_DISPLAY_LENGTH_FILE) {
                displayHeader = headerName.substring(0, MAX_CELL_CONTENT_DISPLAY_LENGTH_FILE - TRUNCATION_MARKER_LEN) + TRUNCATION_MARKER;
            }
            processedHeaders.put(headerName, displayHeader);
            columnWidths.put(headerName, displayHeader.length());
        }

        List<List<String>> processedDataRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<String> processedRow = new ArrayList<>();
            for (String headerName : headers) {
                Object value = row.get(headerName);
                String cellText = (value != null ? value.toString() : "NULL");
                cellText = cellText.replace("\n", " ").replace("\r", " ");

                String displayCellText = cellText;
                if (cellText.length() > MAX_CELL_CONTENT_DISPLAY_LENGTH_FILE) {
                    displayCellText = cellText.substring(0, MAX_CELL_CONTENT_DISPLAY_LENGTH_FILE - TRUNCATION_MARKER_LEN) + TRUNCATION_MARKER;
                }
                processedRow.add(displayCellText);
                columnWidths.put(headerName, Math.max(columnWidths.get(headerName), displayCellText.length()));
            }
            processedDataRows.add(processedRow);
        }

        StringBuilder formattedTable = new StringBuilder();
        String columnSeparator = " | ";
        String headerLineSeparatorPart = "-|-";

        for (int i = 0; i < headers.size(); i++) {
            String headerName = headers.get(i);
            String displayHeader = processedHeaders.get(headerName);
            int width = columnWidths.get(headerName);
            formattedTable.append(String.format("%-" + width + "s", displayHeader));
            if (i < headers.size() - 1) formattedTable.append(columnSeparator);
        }
        formattedTable.append("\n");
        for (int i = 0; i < headers.size(); i++) {
            int width = columnWidths.get(headers.get(i));
            formattedTable.append("-".repeat(width));
            if (i < headers.size() - 1) formattedTable.append(headerLineSeparatorPart);
        }
        formattedTable.append("\n");
        for (List<String> rowData : processedDataRows) {
            for (int i = 0; i < headers.size(); i++) {
                String displayCellText = rowData.get(i);
                int width = columnWidths.get(headers.get(i));
                formattedTable.append(String.format("%-" + width + "s", displayCellText));
                if (i < headers.size() - 1) formattedTable.append(columnSeparator);
            }
            formattedTable.append("\n");
        }

        String tableContentForFile = formattedTable.toString().trim();
        Path tempFile = null;
        try {
            String safeRepoPart = "query_results";
            if (repoUrlForFilename != null && !repoUrlForFilename.isBlank()) {
                String[] urlParts = repoUrlForFilename.split("/");
                if (urlParts.length > 0) {
                    safeRepoPart = urlParts[urlParts.length - 1].replaceAll("[^a-zA-Z0-9.\\-_]", "_").replaceAll("\\.git$", "");
                }
            }
            safeRepoPart = safeRepoPart.length() > 30 ? safeRepoPart.substring(0, 30) : safeRepoPart;
            String filename = safeRepoPart + "_" + System.currentTimeMillis() + ".txt";

            tempFile = Files.createTempFile("cq_txt_", ".txt");
            Files.writeString(tempFile, tableContentForFile, StandardCharsets.UTF_8);
            SendDocument sendDocumentRequest = new SendDocument();
            sendDocumentRequest.setChatId(String.valueOf(chatId));
            sendDocumentRequest.setDocument(new InputFile(tempFile.toFile(), filename));
            sendDocumentRequest.setCaption("✅ Query executed successfully. Results are in the attached TXT file.");
            bot.execute(sendDocumentRequest);
        } catch (IOException | TelegramApiException e) {
            log.error("Failed to send results as TXT file to chat ID {}", chatId, e);
            if (e instanceof IOException) sendMessage(chatId, "❌ Error: Could not generate TXT results file.");
        } finally {
            if (tempFile != null) try { Files.deleteIfExists(tempFile); } catch (IOException e) { log.warn("Failed to delete temp TXT file", e); }
        }
    }
}