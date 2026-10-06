package com.github.lechandonga.dataqualitydriftmonitorjava.tests;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.StringJoiner;

/** Helpers to build contract-conforming CSV / JSONL payloads. */
final class TestData {

    private TestData() {
    }

    static byte[] csv(int rows, double amountBase) {
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("id,amount,category,event_time");
        for (int i = 0; i < rows; i++) {
            joiner.add("id-%d,%.2f,cat-%d,%s".formatted(i, amountBase + (i % 5),
                    i % 3, Instant.now().toString()));
        }
        return joiner.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] jsonl(int rows, double amountBase) {
        StringJoiner joiner = new StringJoiner("\n");
        for (int i = 0; i < rows; i++) {
            joiner.add("{\"id\":\"id-%d\",\"amount\":%.2f,\"category\":\"cat-%d\",\"event_time\":\"%s\"}"
                    .formatted(i, amountBase + (i % 5), i % 3, Instant.now().toString()));
        }
        return joiner.toString().getBytes(StandardCharsets.UTF_8);
    }
}
