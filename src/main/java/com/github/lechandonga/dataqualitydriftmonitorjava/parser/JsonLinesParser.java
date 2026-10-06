package com.github.lechandonga.dataqualitydriftmonitorjava.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.StructuralIssue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON Lines 解析：每行必须是一个 JSON 对象；非对象（数组/标量）同样拒绝。
 */
public class JsonLinesParser {

    private final ObjectMapper objectMapper;

    public JsonLinesParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedBatch parse(String text) {
        List<Map<String, Object>> records = new ArrayList<>();
        List<Integer> recordLines = new ArrayList<>();
        List<StructuralIssue> issues = new ArrayList<>();

        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].stripTrailing();
            if (line.isBlank()) {
                continue;
            }
            int lineNo = i + 1;
            try {
                Object value = objectMapper.readValue(line, Object.class);
                if (!(value instanceof Map<?, ?> rawMap)) {
                    issues.add(new StructuralIssue("MALFORMED_JSON",
                            "第 " + lineNo + " 行不是 JSON 对象，JSON Lines 要求每行为一个对象", lineNo));
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : rawMap.entrySet()) {
                    row.put(String.valueOf(e.getKey()), e.getValue());
                }
                records.add(row);
                recordLines.add(lineNo);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                issues.add(new StructuralIssue("MALFORMED_JSON",
                        "第 " + lineNo + " 行 JSON 语法错误: " + e.getOriginalMessage(), lineNo));
            }
        }
        if (issues.isEmpty() && records.isEmpty()) {
            issues.add(new StructuralIssue("EMPTY_DATA", "提交内容中没有任何记录", 1));
        }
        return new ParsedBatch(records, recordLines, issues);
    }
}
