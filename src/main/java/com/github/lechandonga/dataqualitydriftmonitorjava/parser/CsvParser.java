package com.github.lechandonga.dataqualitydriftmonitorjava.parser;

import com.github.lechandonga.dataqualitydriftmonitorjava.model.StructuralIssue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 支持引号、引号内逗号/换行/双引号转义的 CSV 解析器（RFC 4180 风格）。
 * 第一行为表头，列数不一致、未闭合引号均报 MALFORMED_CSV 并给出行号。
 */
public class CsvParser {

    public ParsedBatch parse(String text) {
        List<StructuralIssue> issues = new ArrayList<>();
        if (text.isBlank()) {
            issues.add(new StructuralIssue("EMPTY_DATA", "提交内容为空", 1));
            return new ParsedBatch(List.of(), List.of(), issues);
        }
        if (text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }

        List<List<String>> rows = new ArrayList<>();
        List<Integer> rowStartLines = new ArrayList<>();
        int pos = 0;
        int len = text.length();
        int line = 1;
        while (pos <= len) {
            int startLine = line;
            List<String> field = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean inQuotes = false;
            boolean hasContent = false;
            boolean terminated = false;
            while (pos < len) {
                char c = text.charAt(pos);
                if (inQuotes) {
                    if (c == '"') {
                        if (pos + 1 < len && text.charAt(pos + 1) == '"') {
                            cur.append('"');
                            pos += 2;
                        } else {
                            inQuotes = false;
                            pos++;
                        }
                    } else {
                        cur.append(c);
                        if (c == '\n') {
                            line++;
                        }
                        pos++;
                    }
                } else if (c == '"') {
                    inQuotes = true;
                    hasContent = true;
                    pos++;
                } else if (c == ',') {
                    field.add(cur.toString());
                    cur.setLength(0);
                    hasContent = false;
                    pos++;
                } else if (c == '\n' || c == '\r') {
                    field.add(cur.toString());
                    cur.setLength(0);
                    if (c == '\r' && pos + 1 < len && text.charAt(pos + 1) == '\n') {
                        pos++;
                    }
                    pos++;
                    line++;
                    terminated = true;
                    break;
                } else {
                    cur.append(c);
                    hasContent = true;
                    pos++;
                }
            }
            if (!terminated) {
                if (pos == len && (hasContent || !field.isEmpty() || inQuotes || rows.isEmpty())) {
                    if (inQuotes) {
                        issues.add(new StructuralIssue("MALFORMED_CSV",
                                "第 " + startLine + " 行起存在未闭合的引号", startLine));
                        break;
                    }
                    field.add(cur.toString());
                    rows.add(field);
                    rowStartLines.add(startLine);
                }
                break;
            }
            if (inQuotes) {
                issues.add(new StructuralIssue("MALFORMED_CSV",
                        "第 " + startLine + " 行起存在未闭合的引号", startLine));
                break;
            }
            rows.add(field);
            rowStartLines.add(startLine);
        }

        if (rows.isEmpty()) {
            if (issues.isEmpty()) {
                issues.add(new StructuralIssue("EMPTY_DATA", "提交内容中没有任何记录", 1));
            }
            return new ParsedBatch(List.of(), List.of(), issues);
        }

        List<String> header = rows.get(0);
        if (header.stream().allMatch(String::isBlank)) {
            issues.add(new StructuralIssue("MALFORMED_CSV", "表头为空", rowStartLines.get(0)));
            return new ParsedBatch(List.of(), List.of(), issues);
        }
        List<String> dupHeaders = new ArrayList<>();
        header.stream().filter(h -> header.stream().filter(h::equals).count() > 1)
                .distinct().forEach(dupHeaders::add);
        if (!dupHeaders.isEmpty()) {
            issues.add(new StructuralIssue("MALFORMED_CSV", "表头存在重复列: " + dupHeaders,
                    rowStartLines.get(0)));
            return new ParsedBatch(List.of(), List.of(), issues);
        }

        List<Map<String, Object>> records = new ArrayList<>();
        List<Integer> recordLines = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> values = rows.get(r);
            int recordLine = rowStartLines.get(r);
            if (values.size() == 1 && values.get(0).isBlank()) {
                continue;
            }
            if (values.size() != header.size()) {
                issues.add(new StructuralIssue("MALFORMED_CSV",
                        "第 " + recordLine + " 行列数 " + values.size()
                                + " 与表头列数 " + header.size() + " 不一致", recordLine));
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                row.put(header.get(c), values.get(c));
            }
            records.add(row);
            recordLines.add(recordLine);
        }
        if (issues.isEmpty() && records.isEmpty()) {
            issues.add(new StructuralIssue("EMPTY_DATA", "表头之后没有任何数据记录",
                    rowStartLines.get(0)));
        }
        return new ParsedBatch(records, recordLines, issues);
    }
}
