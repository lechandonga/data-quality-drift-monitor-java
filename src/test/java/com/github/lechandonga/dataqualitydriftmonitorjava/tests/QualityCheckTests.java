package com.github.lechandonga.dataqualitydriftmonitorjava.tests;

import com.github.lechandonga.dataqualitydriftmonitorjava.ingest.BatchParser;
import com.github.lechandonga.dataqualitydriftmonitorjava.quality.CheckResult;
import com.github.lechandonga.dataqualitydriftmonitorjava.quality.CheckType;
import com.github.lechandonga.dataqualitydriftmonitorjava.quality.QualityCheckConfig;
import com.github.lechandonga.dataqualitydriftmonitorjava.quality.QualityEngine;
import com.github.lechandonga.dataqualitydriftmonitorjava.quality.RuleSet;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchReport;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchService;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务覆盖：质量检查 —— 完整性/唯一性/范围/时效各自可独立启停与配置阈值，
 * 失败结论必须能解释是哪项检查导致。
 */
@SpringBootTest
class QualityCheckTests {

    private static final Logger log = LoggerFactory.getLogger(QualityCheckTests.class);

    @Autowired
    BatchService batchService;

    @Autowired
    com.github.lechandonga.dataqualitydriftmonitorjava.service.RuleService ruleService;

    @Autowired
    BatchParserHolder holder;

    @BeforeEach
    void restoreDefaults() {
        DefaultRules.restore(ruleService);
    }

    @Test
    void duplicateIdsFailUniquenessAndExplainWhy() {
        log.info("[质量检查] 用例：id 重复，期望 FAIL 且由 UNIQUENESS 检查解释");
        byte[] payload = ("id,amount,category,event_time\n"
                + "dup,1.0,a," + Instant.now() + "\n"
                + "dup,2.0,b," + Instant.now() + "\n").getBytes(StandardCharsets.UTF_8);
        BatchReport report = batchService.submit("qc-dup", payload, BatchParser.Format.CSV);
        assertThat(report.verdict()).isEqualTo(BatchVerdict.FAIL);
        CheckResult uniqueness = report.checkResults().stream()
                .filter(r -> r.type() == CheckType.UNIQUENESS).findFirst().orElseThrow();
        assertThat(uniqueness.passed()).isFalse();
        assertThat(uniqueness.detail()).contains("duplicateRatio");
        log.info("[质量检查] 通过：{}", uniqueness.detail());
    }

    @Test
    void outOfRangeAmountFailsRangeCheck() {
        log.info("[质量检查] 用例：amount 超出 [0,10000]，期望 FAIL 且由 RANGE 检查解释");
        byte[] payload = ("id,amount,category,event_time\n"
                + "id-0,99999.0,a," + Instant.now() + "\n").getBytes(StandardCharsets.UTF_8);
        BatchReport report = batchService.submit("qc-range", payload, BatchParser.Format.CSV);
        assertThat(report.verdict()).isEqualTo(BatchVerdict.FAIL);
        CheckResult range = report.checkResults().stream()
                .filter(r -> r.type() == CheckType.RANGE).findFirst().orElseThrow();
        assertThat(range.passed()).isFalse();
        assertThat(range.detail()).contains("outlierRatio");
        log.info("[质量检查] 通过：{}", range.detail());
    }

    @Test
    void staleTimestampFailsFreshnessCheck() {
        log.info("[质量检查] 用例：event_time 过旧，期望 FAIL 且由 FRESHNESS 检查解释");
        byte[] payload = ("id,amount,category,event_time\n"
                + "id-0,1.0,a,2020-01-01T00:00:00Z\n").getBytes(StandardCharsets.UTF_8);
        BatchReport report = batchService.submit("qc-stale", payload, BatchParser.Format.CSV);
        assertThat(report.verdict()).isEqualTo(BatchVerdict.FAIL);
        CheckResult freshness = report.checkResults().stream()
                .filter(r -> r.type() == CheckType.FRESHNESS).findFirst().orElseThrow();
        assertThat(freshness.passed()).isFalse();
        log.info("[质量检查] 通过：{}", freshness.detail());
    }

    @Test
    void disabledCheckIsSkippedAndThresholdsAreRespected() {
        log.info("[质量检查] 用例：检查可独立启停，阈值可调（引擎级）");
        QualityEngine engine = new QualityEngine();
        var outcome = new BatchParser().parse(
                ("id,amount,category,event_time\n"
                        + "a,1.0,x," + Instant.now() + "\n"
                        + "a,2.0,x," + Instant.now() + "\n").getBytes(StandardCharsets.UTF_8),
                BatchParser.Format.CSV, holder.contract());
        RuleSet strict = new RuleSet(List.of(QualityCheckConfig.uniqueness("id", 0.0)));
        RuleSet relaxed = new RuleSet(List.of(QualityCheckConfig.uniqueness("id", 0.6)));
        RuleSet disabled = new RuleSet(List.of(QualityCheckConfig.uniqueness("id", 0.0).withEnabled(false)));
        assertThat(engine.evaluate(outcome.records(), strict).get(0).passed()).isFalse();
        assertThat(engine.evaluate(outcome.records(), relaxed).get(0).passed()).isTrue();
        CheckResult skipped = engine.evaluate(outcome.records(), disabled).get(0);
        assertThat(skipped.skipped()).isTrue();
        assertThat(skipped.passed()).isTrue();
        log.info("[质量检查] 通过：严格阈值拒绝 / 放宽阈值通过 / 停用则跳过");
    }
}
