package com.github.lechandonga.dataqualitydriftmonitorjava.model;

import java.util.List;

/**
 * 字段契约：声明一个数据集允许出现的字段、类型、必填性与枚举取值。
 *
 * @param name    契约（数据集）名称
 * @param version 契约版本；修改契约时应显式提升版本，便于历史批次追溯当时契约
 * @param fields  字段契约列表，字段名不允许重复
 */
public record Contract(
        String name,
        String version,
        List<FieldContract> fields
) {
}
