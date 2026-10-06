package com.github.lechandonga.dataqualitydriftmonitorjava.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据集漂移基线。
 *
 * <ul>
 *   <li>updateStrategy=EXPANDING：每次通过批次都合入基线（基线持续扩张，更新自动完成）。</li>
 *   <li>updateStrategy=ROLLING_WINDOW：仅保留最近 windowSize 个通过批次作为基线，更新自动完成。</li>
 *   <li>updateStrategy=MANUAL：提交永不更新基线，只通过管理接口 rebuild 重建。</li>
 * </ul>
 */
public class Baseline {
    public String dataset;
    public String updateStrategy = "EXPANDING";
    public int windowSize = 10;
    public int minBaselineSamples = 2;
    public int minBatchRows = 1;

    /** 数值列均值偏移 z-score 阈值，超过判漂移 */
    public double numericMeanZThreshold = 3.0;
    /** 数值列范围越界比例阈值（批次值落在基线 [min,max] 之外的占比） */
    public double numericOutOfRangeRateThreshold = 0.05;
    /** 类别列总变差距离 TVD 阈值 */
    public double categoricalTvdThreshold = 0.2;

    /** 基线中每个列的合并画像 */
    public Map<String, ColumnProfile> profile = new HashMap<>();
    /** 构成基线的批次 ID（ROLLING_WINDOW 下有序、有界） */
    public List<String> batchIds = new ArrayList<>();
}
