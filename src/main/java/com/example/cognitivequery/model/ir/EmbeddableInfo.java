package com.example.cognitivequery.model.ir;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class EmbeddableInfo {
    private String javaClassName; // Fully qualified name
    private List<ColumnInfo> fields = new ArrayList<>();
}