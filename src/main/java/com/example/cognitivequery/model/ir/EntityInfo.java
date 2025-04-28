package com.example.cognitivequery.model.ir;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL) // Don't include null fields in JSON
public class EntityInfo {
    private String javaClassName;
    private String tableName;
    private String mappedSuperclass; // Fully qualified name
    private List<ColumnInfo> columns = new ArrayList<>();
    private List<RelationshipInfo> relationships = new ArrayList<>();
}