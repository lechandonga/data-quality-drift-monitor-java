package com.github.lechandonga.dataqualitydriftmonitorjava.service;

import com.github.lechandonga.dataqualitydriftmonitorjava.drift.DriftResult;
import com.github.lechandonga.dataqualitydriftmonitorjava.ingest.RecordRejection;
import com.github.lechandonga.dataqualitydriftmonitorjava.quality.CheckResult;

import java.time.Instant;
import java.util.List;

/**
 * Immutable verdict record for one batch. Once stored it is never modified:
 * rule or threshold changes only affect new submissions, so historical
 * batches always reproduce the conclusion made at their submission time.
 */
public record BatchReport(
        String batchId,
        Instant processedAt,
        String contractVersion,
        String ruleVersion,
        BatchVerdict verdict,
        int acceptedRecords,
        List<RecordRejection> rejections,
        List<CheckResult> checkResults,
        List<DriftResult> driftResults,
        boolean duplicate) {

    public BatchReport asDuplicate() {
        return new BatchReport(batchId, processedAt, contractVersion, ruleVersion, verdict,
                acceptedRecords, rejections, checkResults, driftResults, true);
    }
}
