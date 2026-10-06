package com.github.lechandonga.dataqualitydriftmonitorjava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.dataqualitydriftmonitorjava.check.QualityEngine;
import com.github.lechandonga.dataqualitydriftmonitorjava.drift.DriftEngine;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.Baseline;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.BatchVerdict;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.CheckResult;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.ColumnProfile;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.Contract;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.QualityRules;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.StructuralIssue;
import com.github.lechandonga.dataqualitydriftmonitorjava.parser.CsvParser;
import com.github.lechandonga.dataqualitydriftmonitorjava.parser.EncodingDecoder;
import com.github.lechandonga.dataqualitydriftmonitorjava.parser.JsonLinesParser;
import com.github.lechandonga.dataqualitydriftmonitorjava.parser.ParsedBatch;
import com.github.lechandonga.dataqualitydriftmonitorjava.profile.ProfileBuilder;
import com.github.lechandonga.dataqualitydriftmonitorjava.store.Store;
import com.github.lechandonga.dataqualitydriftmonitorjava.validate.StructuralValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批次提交主流程。整个“查重 → 校验 → 画像 → 漂移 → 基线更新 → 落盘”
 * 在数据集分段锁内完成，因此同一批内容并发重复提交只会成功计数一次，
 * 多批并发提交的画像/基线更新也不会互相覆盖或丢失。
 */
@Service
public class BatchService {

    private static final Logger log = LoggerFactory.getLogger(BatchService.class);

    private final Store store;
    private final ObjectMapper objectMapper;
    private final CsvParser csvParser = new CsvParser();
    private final JsonLinesParser jsonLinesParser;
    private final StructuralValidator structuralValidator = new StructuralValidator();
    private final QualityEngine qualityEngine = new QualityEngine();
    private final ProfileBuilder profileBuilder = new ProfileBuilder();
    private final DriftEngine driftEngine = new DriftEngine();
    private final Clock clock;

    public BatchService(Store store, ObjectMapper objectMapper, Clock clock) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.jsonLinesParser = new JsonLinesParser(objectMapper);
        this.clock = clock;
    }

    public SubmitResult submit(SubmitRequest request) {
        if (request.dataset() == null || request.dataset().isBlank()) {
            throw new IllegalArgumentException("dataset 不能为空");
        }
        String format = request.format() == null ? "" : request.format().toLowerCase();
        if (!"csv".equals(format) && !"jsonl".equals(format)) {
            throw new IllegalArgumentException("format 只支持 csv 或 jsonl");
        }
        byte[] raw = resolveBytes(request);
        byte[] normalized = normalizeCrlf(raw);
        String contentHash = sha256(normalized);

        return store.mutateResult(request.dataset(), state -> {
            BatchVerdict existing = state.verdictsByDataset
                    .getOrDefault(request.dataset(), List.of()).stream()
                    .filter(v -> v.contentHash.equals(contentHash))
                    .findFirst().orElse(null);
            if (existing != null) {
                log.info("重复提交命中已有批次 dataset={}, batchId={}, status={}",
                        request.dataset(), existing.batchId, existing.status);
                return new SubmitResult(existing, true);
            }

            Contract contract = resolveContract(request, state);
            if (contract == null) {
                throw new IllegalArgumentException("数据集 " + request.dataset()
                        + " 未注册契约，请先注册契约或在请求中提供 inlineContract");
            }
            state.contracts.put(contract.name(), contract);

            QualityRules rules = resolveRules(request, state);
            if (rules == null) {
                throw new IllegalArgumentException("数据集 " + request.dataset()
                        + " 没有可用的质量规则，请先注册规则或在请求中提供 inlineRules");
            }
            state.rulesByDatasetVersion
                    .computeIfAbsent(request.dataset(), k -> new LinkedHashMap<>())
                    .put(rules.version(), rules);

            Instant now = Instant.now(clock);
            BatchVerdict verdict = new BatchVerdict();
            verdict.batchId = request.dataset() + "-" + contentHash.substring(0, 12);
            verdict.dataset = request.dataset();
            verdict.contentHash = contentHash;
            verdict.format = format;
            verdict.submittedAt = now;
            verdict.contractVersion = contract.version();
            verdict.rulesVersion = rules.version();
            verdict.contractSnapshot = contract;
            verdict.rulesSnapshot = rules;

            EncodingDecoder.DecodeResult decoded = EncodingDecoder.decode(raw, request.encoding());
            List<StructuralIssue> issues = new ArrayList<>(decoded.issues());

            ParsedBatch parsed = "csv".equals(format)
                    ? csvParser.parse(decoded.text())
                    : jsonLinesParser.parse(decoded.text());
            issues.addAll(parsed.issues);

            if (!issues.isEmpty()) {
                verdict.status = "REJECTED_STRUCTURAL";
                verdict.structuralIssues = List.copyOf(issues);
                verdict.rowCount = parsed == null ? 0 : parsed.records.size();
                log.info("批次结构拒绝 dataset={}, batchId={}, issues={}",
                        request.dataset(), verdict.batchId, issues.size());
            } else {
                StructuralValidator.ValidatedBatch validated = structuralValidator.validate(
                        contract, parsed.records, parsed.recordLines, format);
                verdict.rowCount = validated.records.size();
                verdict.recordIssues = List.copyOf(validated.issues);
                if (!validated.issues.isEmpty()) {
                    verdict.status = "REJECTED_STRUCTURAL";
                    log.info("批次记录级结构拒绝 dataset={}, batchId={}, recordIssues={}",
                            request.dataset(), verdict.batchId, validated.issues.size());
                } else {
                    List<CheckResult> checks = qualityEngine.run(
                            rules, contract, validated.records, now);
                    verdict.checks = checks;
                    boolean qualityPassed = checks.stream()
                            .filter(CheckResult::enabled)
                            .allMatch(CheckResult::passed);
                    verdict.status = qualityPassed ? "ACCEPTED" : "REJECTED_QUALITY";

                    Map<String, ColumnProfile> profiles = profileBuilder.build(
                            contract, validated.records);
                    verdict.profiles = profiles;

                    Baseline baseline = state.baselines.computeIfAbsent(
                            request.dataset(), d -> {
                                Baseline b = new Baseline();
                                b.dataset = d;
                                return b;
                            });
                    verdict.drift = driftEngine.evaluate(baseline, profiles, verdict.rowCount);

                    if (qualityPassed) {
                        updateBaseline(baseline, verdict, profiles, state);
                    }
                    log.info("批次质量判定完成 dataset={}, batchId={}, status={}, drift={}",
                            request.dataset(), verdict.batchId, verdict.status,
                            verdict.drift.decision);
                }
            }

            state.verdictsByDataset
                    .computeIfAbsent(request.dataset(), k -> new ArrayList<>())
                    .add(verdict);
            return new SubmitResult(verdict, false);
        });
    }

    private void updateBaseline(Baseline baseline, BatchVerdict verdict,
                                Map<String, ColumnProfile> profiles, Store.State state) {
        // 所有质量通过批次的画像都登记入 profilesByBatch：
        // MANUAL 模式下提交不改变基线，但 rebuild 时仍能从历史画像完整重建。
        state.profilesByBatch.put(verdict.batchId, profiles);
        String strategy = baseline.updateStrategy == null
                ? "EXPANDING" : baseline.updateStrategy;
        switch (strategy) {
            case "MANUAL" -> {
                // 手动模式：提交不改变基线
            }
            case "ROLLING_WINDOW" -> {
                List<String> ids = new ArrayList<>(baseline.batchIds);
                ids.add(verdict.batchId);
                while (ids.size() > baseline.windowSize) {
                    String removed = ids.remove(0);
                    state.profilesByBatch.remove(removed);
                }
                baseline.batchIds = ids;
                rebuildBaselineProfile(baseline, state);
            }
            default -> {
                baseline.batchIds.add(verdict.batchId);
                rebuildBaselineProfile(baseline, state);
            }
        }
    }

    private void rebuildBaselineProfile(Baseline baseline, Store.State state) {
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

    private Contract resolveContract(SubmitRequest request, Store.State state) {
        if (request.inlineContract() != null) {
            return request.inlineContract();
        }
        return state.contracts.get(request.dataset());
    }

    private QualityRules resolveRules(SubmitRequest request, Store.State state) {
        if (request.inlineRules() != null) {
            return request.inlineRules();
        }
        Map<String, QualityRules> versions = state.rulesByDatasetVersion.get(request.dataset());
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        if (request.rulesVersion() != null) {
            QualityRules pinned = versions.get(request.rulesVersion());
            if (pinned == null) {
                throw new IllegalArgumentException("规则版本 " + request.rulesVersion()
                        + " 不存在；历史规则版本不可被覆盖或删除");
            }
            return pinned;
        }
        return versions.values().stream().reduce((a, b) -> b).orElse(null);
    }

    private byte[] resolveBytes(SubmitRequest request) {
        if (request.contentBase64() != null && request.content() != null) {
            throw new IllegalArgumentException("content 与 contentBase64 只能提供一个");
        }
        if (request.contentBase64() != null) {
            try {
                return Base64.getDecoder().decode(request.contentBase64());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("contentBase64 不是合法的 Base64", e);
            }
        }
        if (request.content() != null) {
            return request.content().getBytes(StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("必须提供 content 或 contentBase64");
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** CRLF 与 LF 在业务上等价，统一按 LF 计算幂等哈希，避免仅换行符差异被当成不同批次。 */
    private byte[] normalizeCrlf(byte[] raw) {
        byte[] out = new byte[raw.length];
        int j = 0;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == '\r' && i + 1 < raw.length && raw[i + 1] == '\n') {
                continue;
            }
            out[j++] = raw[i];
        }
        if (j == raw.length) {
            return raw;
        }
        byte[] trimmed = new byte[j];
        System.arraycopy(out, 0, trimmed, 0, j);
        return trimmed;
    }
}
