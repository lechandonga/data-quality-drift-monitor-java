package com.github.lechandonga.dataqualitydriftmonitorjava.model;

/**
 * 记录级问题，可定位到具体记录行号与字段。
 *
 * @param recordIndex 从 1 开始的记录序号（不含表头）
 * @param line        原始文件行号（CSV 含引号换行时可能与 recordIndex 不同）
 * @param code        拒绝原因码：MISSING_FIELD / TYPE_MISMATCH / NULL_NOT_ALLOWED /
 *                    UNKNOWN_FIELD / ENCODING_ERROR / VALUE_NOT_ALLOWED
 * @param field       涉及字段名（UNKNOWN_FIELD 时为实际出现的多余字段）
 * @param message     人类可读说明
 */
public record RecordIssue(
        int recordIndex,
        int line,
        String code,
        String field,
        String message
) {
}
