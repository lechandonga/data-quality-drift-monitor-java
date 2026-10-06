package com.github.lechandonga.dataqualitydriftmonitorjava.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.HashMap;
import java.util.Map;

/**
 * 单列画像。数值列使用 count/mean/m2（Welford 可合并方差）、min/max；
 * 类别列使用频次表；两类字段均统计空值数。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ColumnProfile {
    public String column;
    public String kind;
    public long totalCount;
    public long nullCount;

    public double mean;
    public double m2;
    public Double min;
    public Double max;

    public Map<String, Long> frequencies = new HashMap<>();
    public long distinctCount;

    public ColumnProfile merge(ColumnProfile other) {
        ColumnProfile r = new ColumnProfile();
        r.column = this.column;
        r.kind = this.kind;
        r.totalCount = this.totalCount + other.totalCount;
        r.nullCount = this.nullCount + other.nullCount;

        if ("numeric".equals(r.kind)) {
            long na = this.nonNullCount();
            long nb = other.nonNullCount();
            long n = na + nb;
            if (n > 0) {
                r.mean = (this.mean * na + other.mean * nb) / n;
                double delta = other.mean - this.mean;
                r.m2 = this.m2 + other.m2 + delta * delta * na * nb / (double) n;
            }
            r.min = mergeMin(this.min, other.min);
            r.max = mergeMax(this.max, other.max);
        } else {
            r.frequencies = new HashMap<>(this.frequencies);
            other.frequencies.forEach((k, v) -> r.frequencies.merge(k, v, Long::sum));
            r.distinctCount = r.frequencies.size();
        }
        return r;
    }

    private static Double mergeMin(Double a, Double b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.min(a, b);
    }

    private static Double mergeMax(Double a, Double b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.max(a, b);
    }

    public long nonNullCount() {
        return totalCount - nullCount;
    }
}
