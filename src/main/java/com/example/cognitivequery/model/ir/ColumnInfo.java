package com.example.cognitivequery.model.ir;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL) // Don't include null/default fields
public class ColumnInfo {
    private String fieldName;
    private String columnName;
    private String javaType;
    private String sqlType; // Best guess
    private boolean primaryKey = false;
    private String generationStrategy; // e.g., IDENTITY, SEQUENCE, AUTO, UUID
    private Boolean nullable; // Use Boolean to distinguish between not set and explicitly false
    private Boolean unique;
    private Integer length;
    private Integer precision;
    private Integer scale;

    // Enum specific
    private Boolean isEnum; // Use Boolean
    private EnumInfo enumInfo;

    // Embedded specific
    private Boolean isEmbeddedAttribute; // Use Boolean
    private String embeddedFromFieldName;
    private String originalEmbeddableFieldName;

    // Inheritance specific
    private Boolean inherited; // Use Boolean
    private String inheritedFromClass; // Fully qualified name

    // Default constructor or custom setters might be needed if Lombok defaults aren't enough
    // Lombok @Data should handle most cases
}