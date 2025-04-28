package com.example.cognitivequery.model.ir;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY) // Don't include empty lists
public class EnumInfo {
    private String storageType; // "STRING" or "ORDINAL"
    private List<String> possibleValues; // Can be empty if not determined
}