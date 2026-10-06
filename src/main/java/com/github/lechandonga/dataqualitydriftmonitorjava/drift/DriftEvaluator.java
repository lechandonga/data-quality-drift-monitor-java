package com.github.lechandonga.dataqualitydriftmonitorjava.drift;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Compares a batch column profile against the baseline profile.
 * Numeric columns use a standardized mean-shift score; categorical columns
 * use PSI (population stability index). When the baseline holds fewer than
 * {@code minSamples} observations the result is INDETERMINATE.
 */
public class DriftEvaluator {

    private final long minSamples;
    private final double numericShiftThreshold;
    private final double psiThreshold;

    public DriftEvaluator(long minSamples, double numericShiftThreshold, double psiThreshold) {
        this.minSamples = minSamples;
        this.numericShiftThreshold = numericShiftThreshold;
        this.psiThreshold = psiThreshold;
    }

    public DriftResult evaluate(String column, ColumnProfile baseline, ColumnProfile batch) {
        if (baseline == null || baseline.count() < minSamples) {
            long have = baseline == null ? 0 : baseline.count();
            return new DriftResult(column, DriftStatus.INDETERMINATE, 0.0,
                    "baseline samples=%d < minSamples=%d, drift not decidable".formatted(have, minSamples));
        }
        if (batch.isCategorical() || baseline.isCategorical()) {
            double psi = psi(baseline.categories(), baseline.count(),
                    batch.categories(), batch.count());
            DriftStatus status = psi > psiThreshold ? DriftStatus.DRIFT : DriftStatus.NO_DRIFT;
            return new DriftResult(column, status, psi,
                    "PSI=%.4f threshold=%.4f baselineN=%d batchN=%d"
                            .formatted(psi, psiThreshold, baseline.count(), batch.count()));
        }
        double std = baseline.stddev();
        double score = std == 0.0
                ? (batch.mean() == baseline.mean() ? 0.0 : Double.POSITIVE_INFINITY)
                : Math.abs(batch.mean() - baseline.mean()) / std;
        DriftStatus status = score > numericShiftThreshold ? DriftStatus.DRIFT : DriftStatus.NO_DRIFT;
        return new DriftResult(column, status, score,
                "meanShift=%.4f sigma (baselineMean=%.4f batchMean=%.4f baselineStd=%.4f) threshold=%.4f"
                        .formatted(score, baseline.mean(), batch.mean(), std, numericShiftThreshold));
    }

    private double psi(Map<String, Long> baseline, long baselineN,
                       Map<String, Long> batch, long batchN) {
        double epsilon = 1e-4;
        Set<String> keys = new HashSet<>();
        keys.addAll(baseline.keySet());
        keys.addAll(batch.keySet());
        double psi = 0.0;
        for (String key : keys) {
            double expected = Math.max((double) baseline.getOrDefault(key, 0L) / baselineN, epsilon);
            double actual = batchN == 0 ? epsilon
                    : Math.max((double) batch.getOrDefault(key, 0L) / batchN, epsilon);
            psi += (actual - expected) * Math.log(actual / expected);
        }
        return psi;
    }
}
