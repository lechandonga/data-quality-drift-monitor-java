package com.github.lechandonga.dataqualitydriftmonitorjava.ingest;

import java.util.List;

public record ParseOutcome(List<ParsedRecord> records, List<RecordRejection> rejections) {

    public boolean hasRejections() {
        return !rejections.isEmpty();
    }
}
