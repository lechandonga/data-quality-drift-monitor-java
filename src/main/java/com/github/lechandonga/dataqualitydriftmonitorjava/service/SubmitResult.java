package com.github.lechandonga.dataqualitydriftmonitorjava.service;

import com.github.lechandonga.dataqualitydriftmonitorjava.model.BatchVerdict;

/**
 * 提交响应：批次结论 + 是否命中重复。
 * duplicate 不写回已存储的不可变结论，历史结论对象不会因后续重复提交而变化。
 */
public record SubmitResult(BatchVerdict verdict, boolean duplicate) {
}
