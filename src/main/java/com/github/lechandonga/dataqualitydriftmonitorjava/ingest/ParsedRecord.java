package com.github.lechandonga.dataqualitydriftmonitorjava.ingest;

import java.util.Map;

/** A structurally valid record: typed values keyed by contract field name. */
public record ParsedRecord(int recordIndex, Map<String, Object> values) {
}
