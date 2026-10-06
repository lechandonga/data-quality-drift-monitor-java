package com.github.lechandonga.dataqualitydriftmonitorjava.model;

/**
 * 结构级问题（文件无法解析/编码异常等，与具体记录无关）。
 * code 取值见 {@code RejectCode}：MALFORMED_*、ENCODING_ERROR。
 */
public record StructuralIssue(
        String code,
        String message,
        Integer line
) {
}
