package com.github.lechandonga.dataqualitydriftmonitorjava.validate;

import com.github.lechandonga.dataqualitydriftmonitorjava.model.Contract;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.FieldContract;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.RecordIssue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按字段契约逐记录做结构校验：
 * 缺失字段、类型不符、空值约束、枚举越界、契约外多余字段都会产生可定位的 RecordIssue。
 */
public class StructuralValidator {

    public static class ValidatedBatch {
        public final List<Map<String, Object>> records;
        public final List<RecordIssue> issues;

        public ValidatedBatch(List<Map<String, Object>> records, List<RecordIssue> issues) {
            this.records = records;
            this.issues = issues;
        }
    }

    public ValidatedBatch validate(Contract contract,
                                   List<Map<String, Object>> rawRecords,
                                   List<Integer> recordLines,
                                   String format) {
        List<RecordIssue> issues = new ArrayList<>();
        List<Map<String, Object>> typed = new ArrayList<>();
        Map<String, FieldContract> declared = new LinkedHashMap<>();
        for (FieldContract f : contract.fields()) {
            declared.put(f.name(), f);
        }

        for (int i = 0; i < rawRecords.size(); i++) {
            int recordIndex = i + 1;
            int line = recordLines.get(i);
            Map<String, Object> raw = rawRecords.get(i);
            Map<String, Object> row = new LinkedHashMap<>();

            Set<String> seen = new HashSet<>(raw.keySet());
            for (String extra : raw.keySet()) {
                if (!declared.containsKey(extra)) {
                    issues.add(new RecordIssue(recordIndex, line, "UNKNOWN_FIELD", extra,
                            "契约外的多余字段: " + extra));
                }
            }

            for (FieldContract fc : declared.values()) {
                boolean present = seen.contains(fc.name());
                Object value = present ? raw.get(fc.name()) : null;
                if (present && value == null) {
                    if (fc.required()) {
                        issues.add(new RecordIssue(recordIndex, line, "MISSING_FIELD", fc.name(),
                                "必填字段显式为 null"));
                    } else if (!fc.nullableEffective()) {
                        issues.add(new RecordIssue(recordIndex, line, "NULL_NOT_ALLOWED", fc.name(),
                                "字段不允许为空"));
                    } else {
                        row.put(fc.name(), null);
                    }
                    continue;
                }
                if (!present) {
                    if (fc.required()) {
                        issues.add(new RecordIssue(recordIndex, line, "MISSING_FIELD", fc.name(),
                                "缺少必填字段: " + fc.name()));
                    }
                    continue;
                }
                Object converted;
                try {
                    converted = convert(fc, value, "json".equalsIgnoreCase(format));
                } catch (ConversionException e) {
                    issues.add(new RecordIssue(recordIndex, line, "TYPE_MISMATCH", fc.name(),
                            "字段 " + fc.name() + " 期望类型 " + fc.type() + "，实际值无法转换: "
                                    + e.getMessage()));
                    continue;
                }
                if (converted == null) {
                    if (fc.required()) {
                        issues.add(new RecordIssue(recordIndex, line, "MISSING_FIELD", fc.name(),
                                "必填字段为空字符串"));
                    } else if (!fc.nullableEffective()) {
                        issues.add(new RecordIssue(recordIndex, line, "NULL_NOT_ALLOWED", fc.name(),
                                "字段不允许为空"));
                    } else {
                        row.put(fc.name(), null);
                    }
                    continue;
                }
                if (fc.allowedValues() != null && !fc.allowedValues().isEmpty()
                        && !fc.allowedValues().contains(String.valueOf(converted))) {
                    issues.add(new RecordIssue(recordIndex, line, "VALUE_NOT_ALLOWED", fc.name(),
                            "取值 " + converted + " 不在允许集合 " + fc.allowedValues() + " 内"));
                    continue;
                }
                row.put(fc.name(), converted);
            }
            typed.add(row);
        }
        return new ValidatedBatch(typed, issues);
    }

    private static final class ConversionException extends RuntimeException {
        ConversionException(String message) {
            super(message);
        }
    }

    private Object convert(FieldContract fc, Object value, boolean json) {
        String type = fc.type() == null ? "string" : fc.type();
        if (!json && value instanceof String s) {
            return convertFromString(type, s);
        }
        return switch (type) {
            case "string" -> value instanceof String ? value
                    : (json ? throwType(value) : String.valueOf(value));
            case "integer" -> {
                if (value instanceof Long l) {
                    yield l;
                }
                if (value instanceof Integer in) {
                    yield in.longValue();
                }
                if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
                    yield n.longValue();
                }
                yield throwType(value);
            }
            case "number" -> value instanceof Number n ? n.doubleValue() : throwType(value);
            case "boolean" -> value instanceof Boolean b ? b : throwType(value);
            case "date" -> {
                if (value instanceof String s) {
                    yield LocalDate.parse(s);
                }
                yield throwType(value);
            }
            case "datetime" -> {
                if (value instanceof String s) {
                    yield parseInstant(s);
                }
                yield throwType(value);
            }
            default -> throw new ConversionException("未知契约类型: " + type);
        };
    }

    private Object convertFromString(String type, String raw) {
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return switch (type) {
                case "string" -> raw;
                case "integer" -> Long.parseLong(s);
                case "number" -> Double.parseDouble(s);
                case "boolean" -> {
                    if ("true".equalsIgnoreCase(s)) {
                        yield Boolean.TRUE;
                    }
                    if ("false".equalsIgnoreCase(s)) {
                        yield Boolean.FALSE;
                    }
                    throw new ConversionException("'" + s + "' 不是布尔值");
                }
                case "date" -> LocalDate.parse(s);
                case "datetime" -> parseInstant(s);
                default -> throw new ConversionException("未知契约类型: " + type);
            };
        } catch (NumberFormatException e) {
            throw new ConversionException("'" + s + "' 不是合法的 " + type);
        } catch (DateTimeParseException e) {
            throw new ConversionException("'" + s + "' 不是合法的 " + type + " 时间格式");
        }
    }

    private Instant parseInstant(String s) {
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ignored) {
            // 继续尝试其它常见格式
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续
        }
        try {
            return LocalDateTime.parse(s).atOffset(java.time.ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续
        }
        try {
            return LocalDate.parse(s).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            throw new ConversionException("'" + s + "' 不是合法的 datetime 格式");
        }
    }

    private Object throwType(Object value) {
        throw new ConversionException(String.valueOf(value) + " (" + value.getClass().getSimpleName() + ")");
    }
}
