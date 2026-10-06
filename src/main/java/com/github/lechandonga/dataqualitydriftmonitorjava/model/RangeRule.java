package com.github.lechandonga.dataqualitydriftmonitorjava.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 取值范围/异常值规则（数值列）。
 * min/max 为闭区间；超出区间的值记为越界，越界率超过 maxOutOfRangeRate 即不通过。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RangeRule(
        String column,
        Double min,
        Double max,
        Double maxOutOfRangeRate
) {
}
