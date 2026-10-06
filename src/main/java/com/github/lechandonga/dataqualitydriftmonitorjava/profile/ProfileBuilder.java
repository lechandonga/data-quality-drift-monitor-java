package com.github.lechandonga.dataqualitydriftmonitorjava.profile;

import com.github.lechandonga.dataqualitydriftmonitorjava.model.ColumnProfile;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.Contract;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.FieldContract;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 列级画像构建：integer/number 为数值列（Welford 在线统计），其余为类别列。
 */
public class ProfileBuilder {

    public Map<String, ColumnProfile> build(Contract contract,
                                            java.util.List<Map<String, Object>> records) {
        Map<String, ColumnProfile> profiles = new LinkedHashMap<>();
        for (FieldContract fc : contract.fields()) {
            ColumnProfile p = new ColumnProfile();
            p.column = fc.name();
            boolean numeric = "integer".equals(fc.type()) || "number".equals(fc.type());
            p.kind = numeric ? "numeric" : "categorical";
            p.totalCount = records.size();
            if (numeric) {
                long n = 0;
                double mean = 0;
                double m2 = 0;
                Double min = null;
                Double max = null;
                for (Map<String, Object> row : records) {
                    Object v = row.get(fc.name());
                    if (v == null) {
                        p.nullCount++;
                        continue;
                    }
                    double x = ((Number) v).doubleValue();
                    n++;
                    double delta = x - mean;
                    mean += delta / n;
                    m2 += delta * (x - mean);
                    min = min == null ? x : Math.min(min, x);
                    max = max == null ? x : Math.max(max, x);
                }
                p.mean = mean;
                p.m2 = m2;
                p.min = min;
                p.max = max;
            } else {
                for (Map<String, Object> row : records) {
                    Object v = row.get(fc.name());
                    if (v == null) {
                        p.nullCount++;
                        continue;
                    }
                    p.frequencies.merge(String.valueOf(v), 1L, Long::sum);
                }
                p.distinctCount = p.frequencies.size();
            }
            profiles.put(fc.name(), p);
        }
        return profiles;
    }
}
