package com.github.lechandonga.dataqualitydriftmonitorjava.drift;

public record DriftResult(String column, DriftStatus status, double score, String detail) {
}
