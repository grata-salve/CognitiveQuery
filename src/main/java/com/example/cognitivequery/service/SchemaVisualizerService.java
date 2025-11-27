package com.example.cognitivequery.service;

import com.example.cognitivequery.model.ir.EntityInfo;
import com.example.cognitivequery.model.ir.RelationshipInfo;
import com.example.cognitivequery.model.ir.SchemaInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class SchemaVisualizerService {

    private final WebClient.Builder webClientBuilder;

    /**
     * Generates a DB schema image (PNG bytes)
     */
    public byte[] generateSchemaImage(SchemaInfo schema) {
        if (schema == null || schema.getEntities().isEmpty()) {
            return null;
        }

        // 1. Build the DOT graph
        String dotGraph = buildDotGraph(schema);

        // 2. Make a POST request to QuickChart (no URL length limit)
        try {
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("graph", dotGraph);
            requestBody.put("format", "png");

            return webClientBuilder.build()
                    .post()
                    .uri("https://quickchart.io/graphviz")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(); // Blocking call, as the bot operates within an executor.
        } catch (Exception e) {
            log.error("Failed to fetch schema image from QuickChart", e);
            return null;
        }
    }

    private String buildDotGraph(SchemaInfo schema) {
        StringBuilder dot = new StringBuilder();
        dot.append("digraph DB {");
        dot.append("rankdir=LR;");
        dot.append("node [shape=plaintext];");
        dot.append("graph [bgcolor=transparent];");

        Set<String> processedEdges = new HashSet<>();

        for (EntityInfo entity : schema.getEntities()) {
            String tableName = entity.getTableName();
            // Use HTML-like labels for tables
            dot.append(String.format("\"%s\" [label=<<table border=\"0\" cellborder=\"1\" cellspacing=\"0\">", tableName));
            dot.append(String.format("<tr><td bgcolor=\"lightgrey\"><b>%s</b></td></tr>", tableName));

            entity.getColumns().stream().limit(12).forEach(col -> {
                String icon = col.isPrimaryKey() ? "🔑 " : "";
                String type = col.getSqlType() != null ? col.getSqlType() : "N/A";
                dot.append(String.format("<tr><td align=\"left\">%s%s <font color=\"grey\">%s</font></td></tr>",
                        icon, col.getColumnName(), type));
            });
            dot.append("</table>>];");
        }

        for (EntityInfo entity : schema.getEntities()) {
            if (entity.getRelationships() == null) continue;
            for (RelationshipInfo rel : entity.getRelationships()) {
                String targetTable = findTableNameByClass(schema, rel.getTargetEntityJavaClass());
                String sourceTable = entity.getTableName();

                if (targetTable != null) {
                    String edgeKey = sourceTable + "-" + targetTable;
                    if (!processedEdges.contains(edgeKey)) {
                        String style = getEdgeStyle(rel.getType());
                        dot.append(String.format("\"%s\" -> \"%s\" %s;", sourceTable, targetTable, style));
                        processedEdges.add(edgeKey);
                    }
                }
            }
        }
        dot.append("}");
        return dot.toString();
    }

    private String findTableNameByClass(SchemaInfo schema, String javaClassName) {
        return schema.getEntities().stream()
                .filter(e -> e.getJavaClassName().equals(javaClassName) ||
                        e.getJavaClassName().endsWith("." + javaClassName))
                .map(EntityInfo::getTableName)
                .findFirst()
                .orElse(null);
    }

    private String getEdgeStyle(String relationType) {
        if ("OneToMany".equalsIgnoreCase(relationType)) return "[label=\"1:N\", color=\"#5bc0de\"]";
        else if ("ManyToOne".equalsIgnoreCase(relationType)) return "[label=\"N:1\", color=\"#f0ad4e\"]";
        else if ("ManyToMany".equalsIgnoreCase(relationType)) return "[dir=both, label=\"N:M\", color=\"#d9534f\"]";
        return "[color=\"black\"]";
    }
}