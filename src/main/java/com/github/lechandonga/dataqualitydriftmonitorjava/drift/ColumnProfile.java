package com.github.lechandonga.dataqualitydriftmonitorjava.drift;

import java.util.HashMap;
import java.util.Map;

/**
 * Column-level profile with Welford running statistics for numeric columns
 * and value counts for categorical columns. All mutating methods are
 * synchronized so concurrent batch submissions never lose or overwrite
 * statistics.
 */
public class ColumnProfile {

    private long count;
    private double mean;
    private double m2;
    private final Map<String, Long> categories = new HashMap<>();

    public synchronized void addNumeric(double value) {
        count++;
        double delta = value - mean;
        mean += delta / count;
        m2 += delta * (value - mean);
    }

    public synchronized void addCategorical(String value) {
        count++;
        categories.merge(value, 1L, Long::sum);
    }

    public synchronized void merge(ColumnProfile other) {
        Map<String, Long> otherCategories;
        long otherCount;
        double otherMean;
        double otherM2;
        synchronized (other) {
            otherCount = other.count;
            otherMean = other.mean;
            otherM2 = other.m2;
            otherCategories = new HashMap<>(other.categories);
        }
        if (!otherCategories.isEmpty()) {
            otherCategories.forEach((k, v) -> categories.merge(k, v, Long::sum));
            count += otherCount;
            return;
        }
        if (otherCount == 0) {
            return;
        }
        if (count == 0) {
            count = otherCount;
            mean = otherMean;
            m2 = otherM2;
            return;
        }
        long total = count + otherCount;
        double delta = otherMean - mean;
        mean = mean + delta * otherCount / total;
        m2 = m2 + otherM2 + delta * delta * count * otherCount / total;
        count = total;
    }

    public synchronized long count() {
        return count;
    }

    public synchronized double mean() {
        return mean;
    }

    public synchronized double stddev() {
        return count > 1 ? Math.sqrt(m2 / (count - 1)) : 0.0;
    }

    public synchronized Map<String, Long> categories() {
        return Map.copyOf(categories);
    }

    public synchronized boolean isCategorical() {
        return !categories.isEmpty();
    }

    public synchronized ColumnProfile snapshot() {
        ColumnProfile copy = new ColumnProfile();
        copy.count = this.count;
        copy.mean = this.mean;
        copy.m2 = this.m2;
        copy.categories.putAll(this.categories);
        return copy;
    }
}
