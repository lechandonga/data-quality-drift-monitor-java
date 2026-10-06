package com.github.lechandonga.dataqualitydriftmonitorjava.drift;

public enum DriftStatus {
    /** Baseline has too few samples; no definite conclusion is allowed. */
    INDETERMINATE,
    NO_DRIFT,
    DRIFT
}
