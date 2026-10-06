package com.github.lechandonga.dataqualitydriftmonitorjava.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 质量检查规则集。每项检查可独立启停并配置阈值。
 *
 * <p>规则集本身带 version：批次提交时会快照所使用的规则版本与完整规则内容，
 * 之后即使规则被调整，历史批次的结论仍按当时快照复现，不会被改写。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QualityRules(
        String version,
        boolean completenessEnabled,
        Double maxMissingRate,
        List<String> uniquenessColumns,
        Double maxDuplicateRate,
        List<RangeRule> rangeRules,
        Double defaultMaxOutOfRangeRate,
        FreshnessRule freshness
) {
}
