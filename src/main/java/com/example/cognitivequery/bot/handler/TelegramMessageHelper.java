package com.example.cognitivequery.bot.handler;

import com.example.cognitivequery.bot.CognitiveQueryTelegramBot;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
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


    public void sendSelectResultAsTextInChat(long chatId, List<Map<String, Object>> rows, String repoUrlForFilename, boolean allowCharts) {
        if (rows == null || rows.isEmpty()) {
            sendMessage(chatId, "✅ Query executed successfully, but it returned no rows\\.");
            return;
        }

        if (allowCharts) {
            trySendSelectResultAsChart(chatId, rows);
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

    private void trySendSelectResultAsChart(long chatId, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return;

        List<Map<String, Object>> chartRows = rows.size() > 30 ? rows.subList(0, 30) : rows;
        Set<String> headers = chartRows.get(0).keySet();

        // Must be exactly 2 columns for a simple chat chart
        if (headers.size() != 2) return;

        List<String> headerList = new ArrayList<>(headers);
        String col1 = headerList.get(0);
        String col2 = headerList.get(1);

        String labelCol = null;
        String valueCol = null;

        // Determine which column is numeric (value) and which is the label
        if (isNumeric(chartRows.get(0).get(col1))) {
            valueCol = col1;
            labelCol = col2;
        } else if (isNumeric(chartRows.get(0).get(col2))) {
            valueCol = col2;
            labelCol = col1;
        } else {
            return;
        }

        try {
            List<String> labels = new ArrayList<>();
            List<Number> data = new ArrayList<>();
            boolean looksLikeDate = true;

            for (Map<String, Object> row : chartRows) {
                Object labelObj = row.get(labelCol);
                Object valObj = row.get(valueCol);

                String labelStr = labelObj != null ? labelObj.toString() : "Unknown";
                labels.add(labelStr);

                if (!isDateLike(labelStr)) {
                    looksLikeDate = false;
                }

                if (valObj instanceof Number) {
                    data.add((Number) valObj);
                } else {
                    try {
                        data.add(Double.parseDouble(valObj.toString()));
                    } catch (Exception e) {
                        data.add(0);
                    }
                }
            }

            String chartType = "bar";
            String backgroundColor;
            boolean fill = false;

            if (looksLikeDate) {
                chartType = "line";
                backgroundColor = "'rgba(75, 192, 192, 0.5)'";
                fill = true;
            } else if (chartRows.size() <= 5) {
                chartType = "doughnut";
                backgroundColor = generateJsColorArray(chartRows.size());
            } else {
                chartType = "bar";
                backgroundColor = "'rgba(54, 162, 235, 0.6)'";
            }

            String labelsJson = labels.stream()
                    .map(l -> "\"" + l.replace("\"", "\\\"") + "\"")
                    .collect(Collectors.joining(","));

            String dataJson = data.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));

            String options = "plugins:{title:{display:true,text:'" + labelCol + " vs " + valueCol + "',font:{size:20}}},";
            if (chartType.equals("doughnut")) {
                options += "legend:{position:'right',labels:{font:{size:14}}},";
            } else {
                options += "legend:{display:false},scales:{x:{ticks:{autoSkip:true,maxRotation:45,minRotation:45}},y:{beginAtZero:true}},";
            }

            String datasetConfig = String.format(
                    "{label:'%s', data:[%s], backgroundColor:%s, borderColor:'white', borderWidth:2, fill:%b}",
                    valueCol, dataJson, backgroundColor, fill
            );

            String chartConfig = String.format(
                    "{type:'%s', data:{labels:[%s], datasets:[%s]}, options:{%s}}",
                    chartType, labelsJson, datasetConfig, options
            );

            // Send via QuickChart
            String chartUrl = "https://quickchart.io/chart?c=" + URLEncoder.encode(chartConfig, StandardCharsets.UTF_8)
                    + "&bkg=white";

            SendPhoto sendPhoto = new SendPhoto();
            sendPhoto.setChatId(String.valueOf(chatId));
            sendPhoto.setPhoto(new InputFile(chartUrl));

            String emoji = chartType.equals("line") ? "📈" : (chartType.equals("doughnut") ? "🍩" : "📊");
            sendPhoto.setCaption(emoji + " Visualized: " + labelCol + " vs " + valueCol);

            bot.execute(sendPhoto);

        } catch (Exception e) {
            log.error("Failed to generate chart", e);
        }
    }

    private boolean isNumeric(Object obj) {
        if (obj == null) return false;
        return obj instanceof Number || (obj instanceof String && ((String) obj).matches("-?\\d+(\\.\\d+)?"));
    }

    private boolean isDateLike(String text) {
        if (text == null) return false;
        return text.matches("^\\d{4}-\\d{2}-\\d{2}.*") || text.matches("^\\d{2}\\.\\d{2}\\.\\d{4}.*");
    }

    // Generates a color array for doughnut charts
    private String generateJsColorArray(int size) {
        String[] palette = {
                "'rgba(255, 99, 132, 0.7)'",  // Red
                "'rgba(54, 162, 235, 0.7)'",  // Blue
                "'rgba(255, 206, 86, 0.7)'",  // Yellow
                "'rgba(75, 192, 192, 0.7)'",  // Green
                "'rgba(153, 102, 255, 0.7)'", // Purple
                "'rgba(255, 159, 64, 0.7)'"   // Orange
        };

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < size; i++) {
            sb.append(palette[i % palette.length]);
            if (i < size - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
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

    public void sendImage(long chatId, byte[] imageBytes, String caption) {
        if (imageBytes == null || imageBytes.length == 0) {
            sendMessage(chatId, "❌ Generated image is empty.");
            return;
        }

        SendPhoto sendPhoto = new SendPhoto();
        sendPhoto.setChatId(String.valueOf(chatId));

        java.io.InputStream is = new java.io.ByteArrayInputStream(imageBytes);
        sendPhoto.setPhoto(new InputFile(is, "schema_visualization.png"));

        if (caption != null) {
            sendPhoto.setCaption(caption);
        }

        try {
            bot.execute(sendPhoto);
        } catch (TelegramApiException e) {
            log.error("Failed to send image bytes to chat ID {}: {}", chatId, e.getMessage());
            sendMessage(chatId, "❌ Failed to upload visualization image.");
        }
    }

    public void sendPlainTextMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setParseMode(null);
        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send plain text message to chat {}: {}", chatId, e.getMessage());
        }
    }

    public void editMessage(org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText editMessage) {
        try {
            bot.execute(editMessage);
        } catch (TelegramApiException e) {
            log.error("Failed to edit message", e);
        }
    }

    public void sendSelectResultAsExcelFile(long chatId, List<Map<String, Object>> rows, String repoUrlForFilename) {
        if (rows == null || rows.isEmpty()) {
            sendMessage(chatId, "✅ Query executed successfully, but it returned no rows \\(nothing to export to Excel\\)\\.");
            return;
        }

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Query Results");
            List<String> headers = new ArrayList<>(rows.get(0).keySet());

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
            }

            int rowNum = 1;
            for (Map<String, Object> rowData : rows) {
                Row row = sheet.createRow(rowNum++);
                int colNum = 0;
                for (String header : headers) {
                    Cell cell = row.createCell(colNum++);
                    Object value = rowData.get(header);

                    if (value == null) {
                        cell.setBlank();
                    } else if (value instanceof Number) {
                        cell.setCellValue(((Number) value).doubleValue());
                    } else if (value instanceof Boolean) {
                        cell.setCellValue((Boolean) value);
                    } else if (value instanceof java.time.LocalDateTime) {
                        cell.setCellValue((java.time.LocalDateTime) value);
                        cell.setCellStyle(dateStyle);
                    } else if (value instanceof java.sql.Timestamp) {
                        cell.setCellValue(((java.sql.Timestamp) value).toLocalDateTime());
                        cell.setCellStyle(dateStyle);
                    } else if (value instanceof java.util.Date) {
                        cell.setCellValue((java.util.Date) value);
                        cell.setCellStyle(dateStyle);
                    } else {
                        cell.setCellValue(value.toString());
                    }
                }
            }

            for (int i = 0; i < headers.size(); i++) {
                sheet.autoSizeColumn(i);
            }
            // Apply autofilter to the header row
            sheet.setAutoFilter(new CellRangeAddress(0, rows.size(), 0, headers.size() - 1));

            String safeRepoPart = "report";
            if (repoUrlForFilename != null && !repoUrlForFilename.isBlank()) {
                String[] urlParts = repoUrlForFilename.split("/");
                if (urlParts.length > 0) safeRepoPart = urlParts[urlParts.length - 1].replace(".git", "");
            }
            String filename = safeRepoPart + "_" + System.currentTimeMillis() + ".xlsx";
            Path tempFile = Files.createTempFile("cq_excel_", ".xlsx");

            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                workbook.write(fos);
            }

            SendDocument sendDocumentRequest = new SendDocument();
            sendDocumentRequest.setChatId(String.valueOf(chatId));
            sendDocumentRequest.setDocument(new InputFile(tempFile.toFile(), filename));
            sendDocumentRequest.setCaption("📊 Excel Report generated successfully.");
            bot.execute(sendDocumentRequest);

            Files.deleteIfExists(tempFile);

        } catch (Exception e) {
            log.error("Failed to generate Excel file", e);
            sendPlainTextMessage(chatId, "❌ Error generating Excel file: " + e.getMessage());
        }
    }
}