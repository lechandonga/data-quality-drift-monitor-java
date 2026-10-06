package com.github.lechandonga.dataqualitydriftmonitorjava.ingest;

/** Distinguishable rejection reasons for structural validation. */
public enum RejectionReason {
    ENCODING_ERROR,
    MALFORMED_RECORD,
    MISSING_FIELD,
    TYPE_MISMATCH,
    UNEXPECTED_FIELD
}
