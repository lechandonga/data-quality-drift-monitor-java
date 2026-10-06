package com.github.lechandonga.dataqualitydriftmonitorjava.quality;

/**
 * Outcome of one check on one batch. {@code detail} explains the measured
 * value versus the configured threshold so a failing verdict is explainable.
 */
public record CheckResult(CheckType type, String field, boolean passed, boolean skipped, String detail) {
}
