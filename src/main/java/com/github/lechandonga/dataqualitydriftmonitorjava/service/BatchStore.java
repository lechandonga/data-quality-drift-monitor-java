package com.github.lechandonga.dataqualitydriftmonitorjava.service;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-memory, thread-safe store of batch reports keyed by batchId.
 * {@link #computeIfAbsent} guarantees that a duplicate submission (including
 * a concurrent one) returns the exact same conclusion and is never
 * double-counted.
 */
@Component
public class BatchStore {

    private final ConcurrentHashMap<String, BatchReport> reports = new ConcurrentHashMap<>();

    /**
     * Returns the existing report for {@code batchId}, or atomically computes,
     * stores and returns a new one. The supplier runs at most once per key.
     */
    public StoredBatch computeIfAbsent(String batchId, Supplier<BatchReport> supplier) {
        boolean[] created = {false};
        BatchReport report = reports.computeIfAbsent(batchId, id -> {
            created[0] = true;
            return supplier.get();
        });
        return new StoredBatch(created[0] ? report : report.asDuplicate(), created[0]);
    }

    public Optional<BatchReport> find(String batchId) {
        return Optional.ofNullable(reports.get(batchId));
    }

    public int size() {
        return reports.size();
    }

    public record StoredBatch(BatchReport report, boolean created) {
    }
}
