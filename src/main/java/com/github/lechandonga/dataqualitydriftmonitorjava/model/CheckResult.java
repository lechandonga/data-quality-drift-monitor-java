package com.github.lechandonga.dataqualitydriftmonitorjava.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 单项质量检查的结论：明确说明该检查是否启用、是否通过、实测值、阈值与细节。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckResult(
        String check,
        boolean enabled,
        boolean passed,
        Map<String, Object> metrics,
        Double threshold,
        List<String> reasons
) {
}
