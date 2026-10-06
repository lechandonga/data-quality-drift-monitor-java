package com.github.lechandonga.dataqualitydriftmonitorjava.quality;

/**
 * Configuration of a single quality check. Each check can be toggled
 * independently and carries its own thresholds.
 *
 * @param type            check kind
 * @param enabled         independent on/off switch
 * @param field           target field (null = whole record, for COMPLETENESS)
 * @param maxMissingRatio COMPLETENESS: max allowed ratio of null values [0,1]
 * @param maxDuplicateRatio UNIQUENESS: max allowed ratio of duplicated keys [0,1]
 * @param min             RANGE: inclusive lower bound for numeric field
 * @param max             RANGE: inclusive upper bound for numeric field
 * @param maxOutlierRatio RANGE: max allowed ratio of out-of-range values [0,1]
 * @param maxAgeSeconds   FRESHNESS: max allowed age of a TIMESTAMP field
 */
public record QualityCheckConfig(
        CheckType type,
        boolean enabled,
        String field,
        Double maxMissingRatio,
        Double maxDuplicateRatio,
        Double min,
        Double max,
        Double maxOutlierRatio,
        Long maxAgeSeconds) {

    public static QualityCheckConfig completeness(String field, double maxMissingRatio) {
        return new QualityCheckConfig(CheckType.COMPLETENESS, true, field,
                maxMissingRatio, null, null, null, null, null);
    }

    public static QualityCheckConfig uniqueness(String field, double maxDuplicateRatio) {
        return new QualityCheckConfig(CheckType.UNIQUENESS, true, field,
                null, maxDuplicateRatio, null, null, null, null);
    }

    public static QualityCheckConfig range(String field, double min, double max, double maxOutlierRatio) {
        return new QualityCheckConfig(CheckType.RANGE, true, field,
                null, null, min, max, maxOutlierRatio, null);
    }

    public static QualityCheckConfig freshness(String field, long maxAgeSeconds) {
        return new QualityCheckConfig(CheckType.FRESHNESS, true, field,
                null, null, null, null, null, maxAgeSeconds);
    }

    public QualityCheckConfig withEnabled(boolean newEnabled) {
        return new QualityCheckConfig(type, newEnabled, field, maxMissingRatio,
                maxDuplicateRatio, min, max, maxOutlierRatio, maxAgeSeconds);
    }
}
