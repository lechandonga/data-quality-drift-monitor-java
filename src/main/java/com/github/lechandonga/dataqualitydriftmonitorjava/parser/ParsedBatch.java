package com.github.lechandonga.dataqualitydriftmonitorjava.parser;

import java.util.List;
import java.util.Map;

/**
 * 解析结果：规范化后的记录（值仍为原始类型，类型强转在结构校验阶段完成）
 * 与结构级问题。
 */
public class ParsedBatch {
    public final List<Map<String, Object>> records;
    public final List<Integer> recordLines;
    public final List<com.github.lechandonga.dataqualitydriftmonitorjava.model.StructuralIssue> issues;

    public ParsedBatch(List<Map<String, Object>> records,
                       List<Integer> recordLines,
                       List<com.github.lechandonga.dataqualitydriftmonitorjava.model.StructuralIssue> issues) {
        this.records = records;
        this.recordLines = recordLines;
        this.issues = issues;
    }
}
