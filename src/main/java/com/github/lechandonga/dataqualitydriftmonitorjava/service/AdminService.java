package com.github.lechandonga.dataqualitydriftmonitorjava.service;

import com.github.lechandonga.dataqualitydriftmonitorjava.model.Baseline;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.ColumnProfile;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.Contract;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.QualityRules;
import com.github.lechandonga.dataqualitydriftmonitorjava.store.Store;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 契约/规则/基线的管理操作。
 *
 * <p>规则按 (数据集, 版本) 永久保留：注册新版本只影响后续批次，
 * 已存在批次结论中保存的 rulesSnapshot 永不变更，因此历史判定可复现。</p>
 */
@Service
public class AdminService {

    private final Store store;

    public AdminService(Store store) {
        this.store = store;
    }

    public Contract registerContract(Contract contract) {
        if (contract.name() == null || contract.version() == null || contract.fields() == null) {
            throw new IllegalArgumentException("契约 name/version/fields 均不能为空");
        }
        store.mutate(contract.name(), state -> state.contracts.put(contract.name(), contract));
        return contract;
    }

    public QualityRules registerRules(String dataset, QualityRules rules) {
        if (rules.version() == null) {
            throw new IllegalArgumentException("规则必须带 version（调整规则请显式升版本）");
        }
        store.mutate(dataset, state -> {
            Map<String, QualityRules> versions =
                    state.rulesByDatasetVersion.computeIfAbsent(dataset, k -> new LinkedHashMap<>());
            if (versions.containsKey(rules.version())) {
                throw new IllegalArgumentException("规则版本 " + rules.version()
                        + " 已存在且不可覆盖；请使用新版本号");
            }
            versions.put(rules.version(), rules);
        });
        return rules;
    }

    public Map<String, QualityRules> listRuleVersions(String dataset) {
        return store.read(dataset,
                s -> new LinkedHashMap<>(s.rulesByDatasetVersion.getOrDefault(dataset, Map.of())));
    }

    public Baseline getBaseline(String dataset) {
        Baseline baseline = store.getBaseline(dataset);
        if (baseline == null) {
            throw new IllegalArgumentException("数据集 " + dataset + " 尚不存在基线（提交首批数据后自动创建）");
        }
        return baseline;
    }

    /** 更新基线配置（更新策略、窗口、样本阈值、漂移阈值）。配置变更不改变已存历史结论。 */
    public Baseline updateBaselineConfig(String dataset, Baseline patch) {
        store.mutate(dataset, state -> {
            Baseline baseline = state.baselines.computeIfAbsent(dataset, d -> {
                Baseline b = new Baseline();
                b.dataset = d;
                return b;
            });
            if (patch.updateStrategy != null) {
                baseline.updateStrategy = patch.updateStrategy;
            }
            baseline.windowSize = patch.windowSize;
            baseline.minBaselineSamples = patch.minBaselineSamples;
            baseline.minBatchRows = patch.minBatchRows;
            baseline.numericMeanZThreshold = patch.numericMeanZThreshold;
            baseline.numericOutOfRangeRateThreshold = patch.numericOutOfRangeRateThreshold;
            baseline.categoricalTvdThreshold = patch.categoricalTvdThreshold;
            if ("MANUAL".equals(baseline.updateStrategy)) {
                applyWindowTrim(baseline, state);
            }
        });
        return getBaseline(dataset);
    }

    /**
     * 手动重建基线：按当前策略纳入该数据集所有通过批次（MANUAL 下亦只保留显式 rebuild 的结果），
     * 或在 ROLLING_WINDOW 下保留最近 windowSize 个通过批次。
     */
    public Baseline rebuildBaseline(String dataset) {
        store.mutate(dataset, state -> {
            Baseline baseline = state.baselines.computeIfAbsent(dataset, d -> {
                Baseline b = new Baseline();
                b.dataset = d;
                return b;
            });
            List<String> accepted = state.verdictsByDataset.getOrDefault(dataset, List.of()).stream()
                    .filter(v -> "ACCEPTED".equals(v.status))
                    .map(v -> v.batchId)
                    .toList();
            baseline.batchIds = new java.util.ArrayList<>(accepted);
            applyWindowTrim(baseline, state);
            rebuildProfile(baseline, state);
        });
        return getBaseline(dataset);
    }

    private void applyWindowTrim(Baseline baseline, Store.State state) {
        if ("ROLLING_WINDOW".equals(baseline.updateStrategy)) {
            while (baseline.batchIds.size() > baseline.windowSize) {
                String removed = baseline.batchIds.remove(0);
                state.profilesByBatch.remove(removed);
            }
        }
    }

    private void rebuildProfile(Baseline baseline, Store.State state) {
        Map<String, ColumnProfile> merged = new LinkedHashMap<>();
        for (String id : baseline.batchIds) {
            Map<String, ColumnProfile> p = state.profilesByBatch.get(id);
            if (p == null) {
                continue;
            }
            for (Map.Entry<String, ColumnProfile> e : p.entrySet()) {
                merged.merge(e.getKey(), e.getValue(), ColumnProfile::merge);
            }
        }
        baseline.profile = merged;
    }
}
