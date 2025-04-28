package com.example.cognitivequery.model.ir;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class SchemaInfo {
    private String repositoryUrl;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime analysisTimestamp;

    private List<EntityInfo> entities = new ArrayList<>();
    private List<EmbeddableInfo> embeddables = new ArrayList<>();
}