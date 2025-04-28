package com.example.cognitivequery.model.ir;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RelationshipInfo {
    private String fieldName;
    private String type; // "OneToOne", "OneToMany", "ManyToOne", "ManyToMany"
    private String targetEntityJavaClass;
    private String mappedBy;
    private String fetchType; // LAZY, EAGER
    private List<String> cascadeTypes;

    private boolean owningSide = true;
    private String joinColumnName;
    private String joinTableName;
    private String joinTableJoinColumnName;
    private String joinTableInverseJoinColumnName;

    private Boolean inherited;
    private String inheritedFromClass; // Fully qualified name
}