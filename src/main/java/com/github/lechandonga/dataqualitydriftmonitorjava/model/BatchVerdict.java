package com.github.lechandonga.dataqualitydriftmonitorjava.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 一个批次的完整、不可变结论。
 *
 * <p>status：ACCEPTED（结构+质量全部通过）/ REJECTED_STRUCTURAL（结构校验拒绝）
 * / REJECTED_QUALITY（结构通过但质量检查未通过）。</p>
 *
 * <p>rulesSnapshot / contractSnapshot 为提交时刻的规则与契约快照；
 * 历史结论查询永远返回此对象，不随后续规则调整而变化。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BatchVerdict {
    public String batchId;
    public String dataset;
    public String contentHash;
    public String format;
    public Instant submittedAt;
    public String status;

    public long rowCount;
    public List<RecordIssue> recordIssues = List.of();
    public List<StructuralIssue> structuralIssues = List.of();
    public List<CheckResult> checks = List.of();

    public String contractVersion;
    public String rulesVersion;
    public Contract contractSnapshot;
    public QualityRules rulesSnapshot;

    /** 是否为重复提交（重复内容再次提交时为 true，结论与首次一致且不重复计数） */
    public boolean duplicate;

    public DriftReport drift;

    public Map<String, ColumnProfile> profiles;
}
