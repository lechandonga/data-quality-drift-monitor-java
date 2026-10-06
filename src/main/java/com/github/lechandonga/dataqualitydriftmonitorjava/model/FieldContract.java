package com.github.lechandonga.dataqualitydriftmonitorjava.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单个字段的结构契约。
 *
 * <p>type 支持：string / integer / number / boolean / date(yyyy-MM-dd) / datetime(yyyy-MM-dd'T'HH:mm:ss 或带毫秒、带时区)</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldContract(
        String name,
        String type,
        boolean required,
        Boolean nullable,
        List<String> allowedValues
) {
    public boolean nullableEffective() {
        return nullable == null || nullable;
    }
}
