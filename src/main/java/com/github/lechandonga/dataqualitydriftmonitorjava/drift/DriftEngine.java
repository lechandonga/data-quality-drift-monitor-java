package com.github.lechandonga.dataqualitydriftmonitorjava.drift;

import com.github.lechandonga.dataqualitydriftmonitorjava.model.Baseline;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.ColumnProfile;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.DriftReport;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 漂移判定：
 * <ul>
 *   <li>数值列：批次均值相对基线均值的 z-score（基线标准差含微小平滑）。</li>
 *   <li>类别列：批次与基线分布的总变差距离 TVD。</li>
 *   <li>基线批次数/行数、批次行数不足，或基线列样本不足以估计离散度时 → UNDECIDABLE。</li>
 * </ul>
 */
public class DriftEngine {

    private static final double EPSILON = 1e-9;

    public DriftReport evaluate(Baseline baseline, Map<String, ColumnProfile> batch, long batchRows) {
        DriftReport report = new DriftReport();
        report.batchRows = batchRows;
        report.baselineSampleBatches = baseline.batchIds.size();
        report.baselineSampleRows = baseline.profile.values().stream()
                .mapToLong(p -> p.totalCount).findFirst().orElse(0);

        List<String> reasons = new ArrayList<>();
        if (baseline.batchIds.size() < baseline.minBaselineSamples) {
            reasons.add("基线样本批次 " + baseline.batchIds.size()
                    + " 小于最小要求 " + baseline.minBaselineSamples + "，不可判定");
        }
        if (report.baselineSampleRows <= 0) {
            reasons.add("基线没有有效样本，不可判定");
        }
        if (batchRows < baseline.minBatchRows) {
            reasons.add("本批行数 " + batchRows + " 小于最小可判定行数 "
                    + baseline.minBatchRows + "，不可判定");
        }
        if (!reasons.isEmpty()) {
            report.decision = "UNDECIDABLE";
            report.reasons = reasons;
            return report;
        }

        List<DriftReport.ColumnDrift> columns = new ArrayList<>();
        Set<String> evaluated = new HashSet<>();
        for (Map.Entry<String, ColumnProfile> e : batch.entrySet()) {
            ColumnProfile base = baseline.profile.get(e.getKey());
            if (base == null) {
                continue;
            }
            evaluated.add(e.getKey());
            columns.add(compareColumn(base, e.getValue(), baseline));
        }
        report.columns = columns;

        boolean anyDrift = columns.stream().anyMatch(c -> "DRIFT".equals(c.decision()));
        boolean allUndecidable = evaluated.isEmpty()
                || columns.stream().allMatch(c -> "UNDECIDABLE".equals(c.decision()));
        if (anyDrift) {
            report.decision = "DRIFT";
            columns.stream().filter(c -> "DRIFT".equals(c.decision()))
                    .forEach(c -> reasons.add("列 " + c.column() + " 发生漂移（"
                            + c.metricName() + "=" + c.metric() + "，阈值 " + c.threshold() + "）"));
        } else if (allUndecidable) {
            report.decision = "UNDECIDABLE";
            reasons.add("没有任何列具备可判定的基线样本");
        } else {
            report.decision = "NO_DRIFT";
        }
        report.reasons = reasons;
        return report;
    }

    private DriftReport.ColumnDrift compareColumn(ColumnProfile base, ColumnProfile cur,
                                                  Baseline cfg) {
        if ("numeric".equals(base.kind)) {
            long baseN = base.nonNullCount();
            long curN = cur.nonNullCount();
            if (baseN < 2 || curN == 0) {
                return new DriftReport.ColumnDrift(base.column, "numeric", "UNDECIDABLE",
                        null, cfg.numericMeanZThreshold, "meanZ",
                        "基线非空样本 " + baseN + " 或本批非空样本 " + curN + " 不足");
            }
            double variance = base.m2 / (baseN - 1);
            double std = Math.sqrt(Math.max(variance, 0));
            double z = Math.abs(cur.mean - base.mean) / (std + EPSILON);
            z = Math.round(z * 10000.0) / 10000.0;
            String decision = z > cfg.numericMeanZThreshold ? "DRIFT" : "NO_DRIFT";
            return new DriftReport.ColumnDrift(base.column, "numeric", decision,
                    z, cfg.numericMeanZThreshold, "meanZ",
                    "DRIFT".equals(decision) ? "均值偏移超过 z-score 阈值" : "均值偏移在阈值内");
        }
        long baseN = base.totalCount - base.nullCount;
        long curN = cur.totalCount - cur.nullCount;
        if (baseN == 0 || curN == 0) {
            return new DriftReport.ColumnDrift(base.column, "categorical", "UNDECIDABLE",
                    null, cfg.categoricalTvdThreshold, "tvd",
                    "基线或本批该列没有非空样本");
        }
        Set<String> keys = new HashSet<>(base.frequencies.keySet());
        keys.addAll(cur.frequencies.keySet());
        double tvd = 0;
        for (String k : keys) {
            double pb = base.frequencies.getOrDefault(k, 0L) / (double) baseN;
            double pc = cur.frequencies.getOrDefault(k, 0L) / (double) curN;
            tvd += Math.abs(pb - pc);
        }
        tvd = Math.round((tvd / 2) * 10000.0) / 10000.0;
        String decision = tvd > cfg.categoricalTvdThreshold ? "DRIFT" : "NO_DRIFT";
        return new DriftReport.ColumnDrift(base.column, "categorical", decision,
                tvd, cfg.categoricalTvdThreshold, "tvd",
                "DRIFT".equals(decision) ? "类别分布 TVD 超过阈值" : "类别分布 TVD 在阈值内");
    }
}
