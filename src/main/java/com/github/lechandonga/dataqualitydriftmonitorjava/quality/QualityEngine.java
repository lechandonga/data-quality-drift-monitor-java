package com.github.lechandonga.dataqualitydriftmonitorjava.quality;

import com.github.lechandonga.dataqualitydriftmonitorjava.ingest.ParsedRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure, stateless evaluator: same records + same RuleSet => same results. */
public class QualityEngine {

    public List<CheckResult> evaluate(List<ParsedRecord> records, RuleSet ruleSet) {
        List<CheckResult> results = new ArrayList<>();
        for (QualityCheckConfig check : ruleSet.checks()) {
            if (!check.enabled()) {
                results.add(new CheckResult(check.type(), check.field(), true, true,
                        "check disabled by configuration"));
                continue;
            }
            results.add(switch (check.type()) {
                case COMPLETENESS -> completeness(records, check);
                case UNIQUENESS -> uniqueness(records, check);
                case RANGE -> range(records, check);
                case FRESHNESS -> freshness(records, check);
            });
        }
        return results;
    }

    private CheckResult completeness(List<ParsedRecord> records, QualityCheckConfig check) {
        long missing = 0;
        long total = 0;
        for (ParsedRecord record : records) {
            if (check.field() != null) {
                total++;
                if (record.values().get(check.field()) == null) {
                    missing++;
                }
            } else {
                for (Object value : record.values().values()) {
                    total++;
                    if (value == null) {
                        missing++;
                    }
                }
            }
        }
        double ratio = total == 0 ? 0.0 : (double) missing / total;
        boolean passed = ratio <= check.maxMissingRatio();
        return new CheckResult(CheckType.COMPLETENESS, check.field(), passed, false,
                "missingRatio=%.4f (missing=%d/%d) threshold=%.4f"
                        .formatted(ratio, missing, total, check.maxMissingRatio()));
    }

    private CheckResult uniqueness(List<ParsedRecord> records, QualityCheckConfig check) {
        Set<Object> seen = new HashSet<>();
        long duplicates = 0;
        for (ParsedRecord record : records) {
            Object key = record.values().get(check.field());
            if (key == null) {
                continue;
            }
            if (!seen.add(key)) {
                duplicates++;
            }
        }
        double ratio = records.isEmpty() ? 0.0 : (double) duplicates / records.size();
        boolean passed = ratio <= check.maxDuplicateRatio();
        return new CheckResult(CheckType.UNIQUENESS, check.field(), passed, false,
                "duplicateRatio=%.4f (duplicates=%d/%d) threshold=%.4f"
                        .formatted(ratio, duplicates, records.size(), check.maxDuplicateRatio()));
    }

    private CheckResult range(List<ParsedRecord> records, QualityCheckConfig check) {
        long outliers = 0;
        long total = 0;
        Double minObserved = null;
        Double maxObserved = null;
        for (ParsedRecord record : records) {
            Object value = record.values().get(check.field());
            if (value == null) {
                continue;
            }
            double d = ((Number) value).doubleValue();
            total++;
            minObserved = minObserved == null ? d : Math.min(minObserved, d);
            maxObserved = maxObserved == null ? d : Math.max(maxObserved, d);
            if (d < check.min() || d > check.max()) {
                outliers++;
            }
        }
        double ratio = total == 0 ? 0.0 : (double) outliers / total;
        boolean passed = ratio <= check.maxOutlierRatio();
        return new CheckResult(CheckType.RANGE, check.field(), passed, false,
                "outlierRatio=%.4f (outliers=%d/%d) allowed=[%s,%s] observed=[%s,%s] threshold=%.4f"
                        .formatted(ratio, outliers, total, check.min(), check.max(),
                                minObserved, maxObserved, check.maxOutlierRatio()));
    }

    private CheckResult freshness(List<ParsedRecord> records, QualityCheckConfig check) {
        long stale = 0;
        long total = 0;
        Instant now = Instant.now();
        for (ParsedRecord record : records) {
            Object value = record.values().get(check.field());
            if (value == null) {
                continue;
            }
            total++;
            long ageSeconds = now.getEpochSecond() - ((Instant) value).getEpochSecond();
            if (ageSeconds > check.maxAgeSeconds()) {
                stale++;
            }
        }
        boolean passed = stale == 0;
        return new CheckResult(CheckType.FRESHNESS, check.field(), passed, false,
                "staleRecords=%d/%d maxAgeSeconds=%d".formatted(stale, total, check.maxAgeSeconds()));
    }
}
