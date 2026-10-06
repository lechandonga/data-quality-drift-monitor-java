package com.github.lechandonga.dataqualitydriftmonitorjava.service;

import com.github.lechandonga.dataqualitydriftmonitorjava.contract.Contract;
import com.github.lechandonga.dataqualitydriftmonitorjava.drift.DriftResult;
import com.github.lechandonga.dataqualitydriftmonitorjava.ingest.BatchParser;
import com.github.lechandonga.dataqualitydriftmonitorjava.ingest.ParseOutcome;
import com.github.lechandonga.dataqualitydriftmonitorjava.quality.CheckResult;
import com.github.lechandonga.dataqualitydriftmonitorjava.quality.QualityEngine;
import com.github.lechandonga.dataqualitydriftmonitorjava.quality.RuleSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Orchestrates one batch submission: parse + contract validation, quality
 * checks, drift evaluation, idempotent persistence. All state lives in
 * memory; no external service or database is required.
 */
@Service
public class BatchService {

    private static final Logger log = LoggerFactory.getLogger(BatchService.class);

    private final BatchParser parser = new BatchParser();
    private final QualityEngine qualityEngine = new QualityEngine();

    private final BatchStore batchStore;
    private final RuleService ruleService;
    private final ProfileService profileService;
    private final Contract contract;

    public BatchService(BatchStore batchStore, RuleService ruleService,
                        ProfileService profileService, Contract contract) {
        this.batchStore = batchStore;
        this.ruleService = ruleService;
        this.profileService = profileService;
        this.contract = contract;
    }

    /**
     * Submit a batch. Idempotent on {@code batchId}: a repeated submission
     * (even concurrent) returns the original conclusion and does not update
     * statistics twice.
     */
    public BatchReport submit(String batchId, byte[] payload, BatchParser.Format format) {
        BatchStore.StoredBatch stored = batchStore.computeIfAbsent(batchId,
                () -> process(batchId, payload, format));
        if (!stored.created()) {
            log.info("batchId={} duplicate submission, returning stored verdict={} ruleVersion={}",
                    batchId, stored.report().verdict(), stored.report().ruleVersion());
        }
        return stored.report();
    }

    private BatchReport process(String batchId, byte[] payload, BatchParser.Format format) {
        RuleSet ruleSet = ruleService.activeRuleSet();
        ParseOutcome outcome = parser.parse(payload, format, contract);
        if (outcome.hasRejections()) {
            log.info("batchId={} REJECTED rejections={}", batchId, outcome.rejections());
            return new BatchReport(batchId, Instant.now(), contract.version(), ruleSet.version(),
                    BatchVerdict.REJECTED, 0, outcome.rejections(), List.of(), List.of(), false);
        }
        List<CheckResult> checkResults = qualityEngine.evaluate(outcome.records(), ruleSet);
        boolean passed = checkResults.stream().allMatch(CheckResult::passed);
        BatchVerdict verdict = passed ? BatchVerdict.PASS : BatchVerdict.FAIL;
        List<DriftResult> driftResults =
                profileService.evaluateAndMaybeUpdate(contract, outcome.records(), verdict);
        log.info("batchId={} verdict={} ruleVersion={} failedChecks={} drift={}",
                batchId, verdict, ruleSet.version(),
                checkResults.stream().filter(r -> !r.passed()).toList(), driftResults);
        return new BatchReport(batchId, Instant.now(), contract.version(), ruleSet.version(),
                verdict, outcome.records().size(), List.of(), checkResults, driftResults, false);
    }
}
