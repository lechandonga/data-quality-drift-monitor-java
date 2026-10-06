package com.github.lechandonga.dataqualitydriftmonitorjava.ingest;

/**
 * A single rejected record. {@code recordIndex} is 1-based within the batch
 * payload so callers can locate the offending record.
 */
public record RecordRejection(int recordIndex, RejectionReason reason, String field, String detail) {

    @Override
    public String toString() {
        return "record#" + recordIndex + " " + reason
                + (field != null ? " field=" + field : "")
                + " : " + detail;
    }
}
