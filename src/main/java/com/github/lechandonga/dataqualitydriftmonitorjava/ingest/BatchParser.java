package com.github.lechandonga.dataqualitydriftmonitorjava.ingest;

import com.github.lechandonga.dataqualitydriftmonitorjava.contract.Contract;
import com.github.lechandonga.dataqualitydriftmonitorjava.contract.DataType;
import com.github.lechandonga.dataqualitydriftmonitorjava.contract.FieldDefinition;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses CSV and JSON-lines payloads and validates every record against the
 * declared {@link Contract}. Nothing is silently dropped: every structural
 * problem produces a {@link RecordRejection} with a distinguishable reason
 * and a 1-based record index.
 */
public class BatchParser {

    public enum Format { CSV, JSONL }

    public ParseOutcome parse(byte[] payload, Format format, Contract contract) {
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(payload))
                    .toString();
        } catch (CharacterCodingException e) {
            return new ParseOutcome(List.of(), List.of(new RecordRejection(
                    0, RejectionReason.ENCODING_ERROR, null,
                    "payload is not valid UTF-8: " + e.getMessage())));
        }
        return switch (format) {
            case CSV -> parseCsv(text, contract);
            case JSONL -> parseJsonLines(text, contract);
        };
    }

    private ParseOutcome parseCsv(String text, Contract contract) {
        List<ParsedRecord> records = new ArrayList<>();
        List<RecordRejection> rejections = new ArrayList<>();
        List<String[]> rows = csvRows(text);
        if (rows.isEmpty()) {
            rejections.add(new RecordRejection(0, RejectionReason.MALFORMED_RECORD, null,
                    "empty CSV payload, header row required"));
            return new ParseOutcome(records, rejections);
        }
        String[] header = rows.get(0);
        List<FieldDefinition> columns = new ArrayList<>();
        boolean headerOk = true;
        for (String column : header) {
            FieldDefinition def = contract.field(column.trim());
            if (def == null) {
                rejections.add(new RecordRejection(0, RejectionReason.UNEXPECTED_FIELD, column.trim(),
                        "CSV header column is not declared in contract " + contract.version()));
                headerOk = false;
            } else {
                columns.add(def);
            }
        }
        if (!headerOk) {
            return new ParseOutcome(records, rejections);
        }
        for (int i = 1; i < rows.size(); i++) {
            int recordIndex = i; // 1-based data record index (header excluded)
            String[] cells = rows.get(i);
            if (cells.length != columns.size()) {
                rejections.add(new RecordRejection(recordIndex, RejectionReason.MALFORMED_RECORD, null,
                        "expected " + columns.size() + " columns but found " + cells.length));
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            boolean ok = true;
            for (int c = 0; c < columns.size(); c++) {
                FieldDefinition def = columns.get(c);
                String raw = cells[c].trim();
                if (raw.isEmpty()) {
                    if (def.required()) {
                        rejections.add(new RecordRejection(recordIndex, RejectionReason.MISSING_FIELD,
                                def.name(), "required field is empty"));
                        ok = false;
                    } else {
                        values.put(def.name(), null);
                    }
                    continue;
                }
                Object typed = convert(raw, def);
                if (typed instanceof ConversionFailure failure) {
                    rejections.add(new RecordRejection(recordIndex, RejectionReason.TYPE_MISMATCH,
                            def.name(), failure.message()));
                    ok = false;
                } else {
                    values.put(def.name(), typed);
                }
            }
            if (ok) {
                records.add(new ParsedRecord(recordIndex, values));
            }
        }
        return new ParseOutcome(records, rejections);
    }

    private ParseOutcome parseJsonLines(String text, Contract contract) {
        List<ParsedRecord> records = new ArrayList<>();
        List<RecordRejection> rejections = new ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String[] lines = text.split("\\R");
        int recordIndex = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            recordIndex++;
            com.fasterxml.jackson.databind.JsonNode node;
            try {
                node = mapper.readTree(line);
            } catch (Exception e) {
                rejections.add(new RecordRejection(recordIndex, RejectionReason.MALFORMED_RECORD, null,
                        "line is not valid JSON: " + e.getMessage()));
                continue;
            }
            if (!node.isObject()) {
                rejections.add(new RecordRejection(recordIndex, RejectionReason.MALFORMED_RECORD, null,
                        "JSON line must be an object"));
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            boolean ok = true;
            var it = node.fieldNames();
            while (it.hasNext()) {
                String name = it.next();
                if (contract.field(name) == null) {
                    rejections.add(new RecordRejection(recordIndex, RejectionReason.UNEXPECTED_FIELD, name,
                            "field is not declared in contract " + contract.version()));
                    ok = false;
                }
            }
            for (FieldDefinition def : contract.fields()) {
                com.fasterxml.jackson.databind.JsonNode value = node.get(def.name());
                if (value == null || value.isNull()) {
                    if (def.required()) {
                        rejections.add(new RecordRejection(recordIndex, RejectionReason.MISSING_FIELD,
                                def.name(), "required field is absent or null"));
                        ok = false;
                    } else {
                        values.put(def.name(), null);
                    }
                    continue;
                }
                Object typed = convertJson(value, def);
                if (typed instanceof ConversionFailure failure) {
                    rejections.add(new RecordRejection(recordIndex, RejectionReason.TYPE_MISMATCH,
                            def.name(), failure.message()));
                    ok = false;
                } else {
                    values.put(def.name(), typed);
                }
            }
            if (ok) {
                records.add(new ParsedRecord(recordIndex, values));
            }
        }
        return new ParseOutcome(records, rejections);
    }

    private record ConversionFailure(String message) {
    }

    private Object convert(String raw, FieldDefinition def) {
        DataType type = def.type();
        try {
            return switch (type) {
                case STRING -> raw;
                case INTEGER -> Long.parseLong(raw);
                case DOUBLE -> Double.parseDouble(raw);
                case BOOLEAN -> {
                    if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
                        yield Boolean.parseBoolean(raw);
                    }
                    throw new IllegalArgumentException("not a boolean");
                }
                case TIMESTAMP -> Instant.parse(raw);
            };
        } catch (RuntimeException e) {
            return new ConversionFailure("value '" + raw + "' cannot be parsed as " + type);
        }
    }

    private Object convertJson(com.fasterxml.jackson.databind.JsonNode value, FieldDefinition def) {
        DataType type = def.type();
        boolean valid = switch (type) {
            case STRING -> value.isTextual();
            case INTEGER -> value.isIntegralNumber();
            case DOUBLE -> value.isNumber();
            case BOOLEAN -> value.isBoolean();
            case TIMESTAMP -> value.isTextual() && isInstant(value.asText());
        };
        if (!valid) {
            return new ConversionFailure("JSON value " + value + " is not a " + type);
        }
        return switch (type) {
            case STRING -> value.asText();
            case INTEGER -> value.asLong();
            case DOUBLE -> value.asDouble();
            case BOOLEAN -> value.asBoolean();
            case TIMESTAMP -> Instant.parse(value.asText());
        };
    }

    private boolean isInstant(String text) {
        try {
            Instant.parse(text);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Minimal CSV reader supporting quoted cells and escaped quotes. */
    private List<String[]> csvRows(String text) {
        List<String[]> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                current.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                current.add(cell.toString());
                cell.setLength(0);
                rows.add(current.toArray(new String[0]));
                current = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        if (cell.length() > 0 || !current.isEmpty()) {
            current.add(cell.toString());
            rows.add(current.toArray(new String[0]));
        }
        rows.removeIf(r -> r.length == 1 && r[0].isBlank());
        return rows;
    }
}
