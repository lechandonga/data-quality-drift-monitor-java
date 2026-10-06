package com.github.lechandonga.dataqualitydriftmonitorjava.check;

import com.github.lechandonga.dataqualitydriftmonitorjava.model.CheckResult;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.Contract;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.FieldContract;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.FreshnessRule;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.QualityRules;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.RangeRule;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 质量检查引擎：完整性、唯一性、取值范围、时效性四项可独立启停。
 * 每项返回 {@link CheckResult}，包含实测指标、阈值与未通过原因。
 */
public class QualityEngine {

    public List<CheckResult> run(QualityRules rules, Contract contract,
                                 List<Map<String, Object>> records, Instant evaluatedAt) {
        List<CheckResult> results = new ArrayList<>();
        results.add(completeness(rules, contract, records));
        results.add(uniqueness(rules, records));
        results.add(rangeChecks(rules, records));
        results.add(freshness(rules, records, evaluatedAt));
        return results;
    }

    private CheckResult completeness(QualityRules rules, Contract contract,
                                     List<Map<String, Object>> records) {
        if (!rules.completenessEnabled()) {
            return disabled("completeness", null);
        }
        long cells = (long) contract.fields().size() * records.size();
        long missing = 0;
        Map<String, Long> missingByColumn = new LinkedHashMap<>();
        for (FieldContract f : contract.fields()) {
            long colMissing = 0;
            for (Map<String, Object> row : records) {
                Object v = row.get(f.name());
                if (v == null) {
                    colMissing++;
                }
            }
            missingByColumn.put(f.name(), colMissing);
            missing += colMissing;
        }
        double rate = cells == 0 ? 0 : (double) missing / cells;
        double threshold = rules.maxMissingRate() == null ? 0.0 : rules.maxMissingRate();
        boolean passed = rate <= threshold;
        List<String> reasons = new ArrayList<>();
        if (!passed) {
            missingByColumn.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .forEach(e -> reasons.add("字段 " + e.getKey() + " 缺失 " + e.getValue()
                            + "/" + records.size()));
        }
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("missingCells", missing);
        metrics.put("totalCells", cells);
        metrics.put("missingRate", round4(rate));
        metrics.put("missingByColumn", missingByColumn);
        return new CheckResult("completeness", true, passed, metrics, threshold,
                reasons.isEmpty() ? List.of() : reasons);
    }

    private CheckResult uniqueness(QualityRules rules, List<Map<String, Object>> records) {
        List<String> columns = rules.uniquenessColumns();
        if (columns == null || columns.isEmpty()) {
            return disabled("uniqueness", null);
        }
        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> row : records) {
            List<Object> key = new ArrayList<>();
            boolean complete = true;
            for (String c : columns) {
                Object v = row.get(c);
                if (v == null) {
                    complete = false;
                    break;
                }
                key.add(v);
            }
            if (!complete) {
                continue;
            }
            counts.merge(key.toString(), 1L, Long::sum);
        }
        long duplicateRows = counts.values().stream().filter(c -> c > 1).mapToLong(Long::longValue).sum();
        double rate = records.isEmpty() ? 0 : (double) duplicateRows / records.size();
        double threshold = rules.maxDuplicateRate() == null ? 0.0 : rules.maxDuplicateRate();
        boolean passed = rate <= threshold;
        List<String> reasons = new ArrayList<>();
        if (!passed) {
            long dupGroups = counts.values().stream().filter(c -> c > 1).count();
            reasons.add("组合键 " + columns + " 存在 " + dupGroups + " 组重复，涉及 "
                    + duplicateRows + " 条记录");
        }
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("keyColumns", columns);
        metrics.put("duplicateRows", duplicateRows);
        metrics.put("duplicateRate", round4(rate));
        return new CheckResult("uniqueness", true, passed, metrics, threshold, reasons);
    }

    private CheckResult rangeChecks(QualityRules rules, List<Map<String, Object>> records) {
        List<RangeRule> rangeRules = rules.rangeRules();
        if (rangeRules == null || rangeRules.isEmpty()) {
            return disabled("range", null);
        }
        List<String> reasons = new ArrayList<>();
        Map<String, Object> metrics = new HashMap<>();
        boolean allPassed = true;
        for (RangeRule rr : rangeRules) {
            long evaluated = 0;
            long out = 0;
            for (Map<String, Object> row : records) {
                Object v = row.get(rr.column());
                if (!(v instanceof Number n)) {
                    continue;
                }
                evaluated++;
                double d = n.doubleValue();
                if ((rr.min() != null && d < rr.min()) || (rr.max() != null && d > rr.max())) {
                    out++;
                }
            }
            double rate = evaluated == 0 ? 0 : (double) out / evaluated;
            double threshold = rr.maxOutOfRangeRate() != null
                    ? rr.maxOutOfRangeRate()
                    : (rules.defaultMaxOutOfRangeRate() == null ? 0.0 : rules.defaultMaxOutOfRangeRate());
            boolean passed = rate <= threshold;
            Map<String, Object> m = new HashMap<>();
            m.put("evaluated", evaluated);
            m.put("outOfRange", out);
            m.put("outOfRangeRate", round4(rate));
            m.put("min", rr.min());
            m.put("max", rr.max());
            m.put("passed", passed);
            metrics.put(rr.column(), m);
            if (!passed) {
                allPassed = false;
                reasons.add("字段 " + rr.column() + " 越界 " + out + "/" + evaluated
                        + "（阈值 " + threshold + "）");
            }
        }
        return new CheckResult("range", true, allPassed, metrics, null, reasons);
    }

    private CheckResult freshness(QualityRules rules, List<Map<String, Object>> records,
                                  Instant evaluatedAt) {
        FreshnessRule fr = rules.freshness();
        if (fr == null || fr.timestampColumn() == null || fr.maxAge() == null) {
            return disabled("freshness", null);
        }
        Duration maxAge;
        try {
            maxAge = Duration.parse(fr.maxAge());
        } catch (Exception e) {
            return new CheckResult("freshness", true, false, Map.of(), null,
                    List.of("maxAge 配置无法解析: " + fr.maxAge()));
        }
        Instant cutoff = evaluatedAt.minus(maxAge);
        Instant latest = null;
        long evaluated = 0;
        long stale = 0;
        for (Map<String, Object> row : records) {
            Object v = row.get(fr.timestampColumn());
            if (v instanceof Instant instant) {
                evaluated++;
                if (instant.isBefore(cutoff)) {
                    stale++;
                }
                if (latest == null || instant.isAfter(latest)) {
                    latest = instant;
                }
            }
        }
        double staleRate = evaluated == 0 ? 0 : (double) stale / evaluated;
        double threshold = fr.maxStaleRate() == null ? 0.0 : fr.maxStaleRate();
        boolean passed = evaluated > 0 && latest != null && !latest.isBefore(cutoff)
                && staleRate <= threshold;
        List<String> reasons = new ArrayList<>();
        if (evaluated == 0) {
            reasons.add("时间字段 " + fr.timestampColumn() + " 没有可评估的时间值");
        } else if (latest != null && latest.isBefore(cutoff)) {
            reasons.add("最新数据时间 " + latest + " 早于截止时间 " + cutoff);
        } else if (staleRate > threshold) {
            reasons.add("过期记录占比 " + round4(staleRate) + " 超过阈值 " + threshold);
        }
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("timestampColumn", fr.timestampColumn());
        metrics.put("evaluated", evaluated);
        metrics.put("latest", latest == null ? null : latest.toString());
        metrics.put("cutoff", cutoff.toString());
        metrics.put("staleRows", stale);
        metrics.put("staleRate", round4(staleRate));
        return new CheckResult("freshness", true, passed, metrics, threshold, reasons);
    }

    private CheckResult disabled(String name, Object unused) {
        return new CheckResult(name, false, true, Map.of("enabled", false), null, List.of());
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
