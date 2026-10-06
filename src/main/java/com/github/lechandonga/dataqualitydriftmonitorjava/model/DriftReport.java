package com.github.lechandonga.dataqualitydriftmonitorjava.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 漂移判定结论。
 * decision=UNDECIDABLE 表示基线或本批样本不足，此时不能给出确定结论。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DriftReport {
    public String decision;
    public List<ColumnDrift> columns = List.of();
    public List<String> reasons = List.of();
    public int baselineSampleBatches;
    public long baselineSampleRows;
    public long batchRows;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ColumnDrift(
            String column,
            String kind,
            String decision,
            Double metric,
            double threshold,
            String metricName,
            String reason
    ) {
    }
}
