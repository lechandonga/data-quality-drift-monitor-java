package com.github.lechandonga.dataqualitydriftmonitorjava.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 时效性规则：timestampColumn 中的最大时间戳不得早于 (校验时刻 - maxAge)。
 * maxAge 为 {@link java.time.Duration} 文本，如 PT1H、P2D。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FreshnessRule(
        String timestampColumn,
        String maxAge,
        Double maxStaleRate
) {
}
