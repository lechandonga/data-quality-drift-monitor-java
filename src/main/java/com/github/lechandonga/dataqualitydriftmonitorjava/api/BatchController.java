package com.github.lechandonga.dataqualitydriftmonitorjava.api;

import com.github.lechandonga.dataqualitydriftmonitorjava.ingest.BatchParser;
import com.github.lechandonga.dataqualitydriftmonitorjava.quality.QualityCheckConfig;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchReport;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchService;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchStore;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.ProfileService;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.RuleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class BatchController {

    private final BatchService batchService;
    private final BatchStore batchStore;
    private final RuleService ruleService;
    private final ProfileService profileService;

    public BatchController(BatchService batchService, BatchStore batchStore,
                           RuleService ruleService, ProfileService profileService) {
        this.batchService = batchService;
        this.batchStore = batchStore;
        this.ruleService = ruleService;
        this.profileService = profileService;
    }

    /**
     * Submit a batch. Body is the raw CSV or JSON-lines payload; the batch id
     * (idempotency key) comes from the X-Batch-Id header.
     */
    @PostMapping(value = "/batches", consumes = {"text/csv", "application/x-ndjson", "text/plain", "application/json"})
    public ResponseEntity<?> submit(@RequestHeader("X-Batch-Id") String batchId,
                                    @RequestParam(defaultValue = "csv") String format,
                                    @RequestBody byte[] payload) {
        BatchParser.Format parsedFormat;
        try {
            parsedFormat = BatchParser.Format.valueOf(format.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "unsupported format '" + format + "', use csv or jsonl"));
        }
        BatchReport report = batchService.submit(batchId, payload, parsedFormat);
        return switch (report.verdict()) {
            case REJECTED -> ResponseEntity.unprocessableEntity().body(report);
            default -> ResponseEntity.ok(report);
        };
    }

    /** Fetch the stored (immutable) report of a historical batch. */
    @GetMapping("/batches/{batchId}")
    public ResponseEntity<BatchReport> report(@PathVariable String batchId) {
        return batchStore.find(batchId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Replace the active quality rules; creates a new rule version. */
    @PostMapping("/rules")
    public Map<String, String> updateRules(@RequestBody List<QualityCheckConfig> checks) {
        ruleService.updateRules(checks);
        return Map.of("ruleVersion", ruleService.activeRuleSet().version());
    }

    @GetMapping("/rules")
    public Map<String, Object> rules() {
        return Map.of("ruleVersion", ruleService.activeRuleSet().version(),
                "checks", ruleService.activeRuleSet().checks());
    }

    /** Inspect drift baseline state and the active baseline update strategy. */
    @GetMapping("/drift/baselines/{column}")
    public Map<String, Object> baseline(@PathVariable String column) {
        return Map.of("column", column,
                "baselineSamples", profileService.baselineSamples(column),
                "baselineStrategy", profileService.strategy());
    }
}
