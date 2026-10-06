package com.github.lechandonga.dataqualitydriftmonitorjava.tests;

import com.github.lechandonga.dataqualitydriftmonitorjava.model.BatchVerdict;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.CheckResult;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.FreshnessRule;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.QualityRules;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.RangeRule;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchService;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.SubmitRequest;
import com.github.lechandonga.dataqualitydriftmonitorjava.store.Store;
import com.github.lechandonga.dataqualitydriftmonitorjava.tests.support.AbstractApiTest;
import com.github.lechandonga.dataqualitydriftmonitorjava.tests.support.BusinessCoverage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@BusinessCoverage("质量检查：完整性/唯一性/取值范围/时效性独立启停与阈值，结论说明失败检查")
class QualityCheckTest extends AbstractApiTest {

    @Autowired
    private Store store;

    private final Clock fixed = Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC);
    private BatchService service;

    @BeforeEach
    void setUp() {
        store.resetForTesting();
        service = new BatchService(store, objectMapper, fixed);
    }

    private BatchVerdict submit(QualityRules rules, String jsonl) {
        SubmitRequest req = new SubmitRequest("orders", "jsonl", null, null, jsonl,
                null, standardContract("orders"), rules);
        return service.submit(req).verdict();
    }

    private String row(long id, int age, String time) {
        return "{\"id\":" + id + ",\"name\":\"n" + id + "\",\"age\":" + age
                + ",\"level\":\"A\",\"event_time\":\"" + time + "\"}";
    }

    @Test
    @DisplayName("全部检查通过 → ACCEPTED，四项检查均 enabled/passed")
    void allPass() {
        String jsonl = row(1, 20, "2026-10-06T09:30:00Z") + "\n"
                + row(2, 40, "2026-10-06T09:40:00Z") + "\n";
        BatchVerdict v = submit(standardRules(), jsonl);
        assertThat(v.status).isEqualTo("ACCEPTED");
        assertThat(v.checks).hasSize(4);
        assertThat(v.checks).allMatch(CheckResult::enabled);
        assertThat(v.checks).allMatch(CheckResult::passed);
    }

    @Test
    @DisplayName("完整性失败：缺失率超阈值，结论明确指向 completeness")
    void completenessFailure() {
        QualityRules rules = new QualityRules("q1", true, 0.05,
                List.of(), null, List.of(), null, null);
        String jsonl = row(1, 20, "2026-10-06T09:30:00Z").replace(",\"level\":\"A\"", "")
                + "\n" + row(2, 40, "2026-10-06T09:30:00Z").replace(",\"level\":\"A\"", "") + "\n";
        BatchVerdict v = submit(rules, jsonl);
        assertThat(v.status).isEqualTo("REJECTED_QUALITY");
        CheckResult completeness = byName(v, "completeness");
        assertThat(completeness.passed()).isFalse();
        assertThat((double) completeness.metrics().get("missingRate")).isEqualTo(0.2);
        assertThat(completeness.reasons()).anyMatch(r -> r.contains("level"));
    }

    @Test
    @DisplayName("唯一性失败：id 重复导致 uniqueness 不通过，重复率与阈值可见")
    void uniquenessFailure() {
        String jsonl = row(1, 20, "2026-10-06T09:30:00Z") + "\n"
                + row(1, 30, "2026-10-06T09:30:00Z") + "\n"
                + row(2, 40, "2026-10-06T09:30:00Z") + "\n";
        BatchVerdict v = submit(standardRules(), jsonl);
        assertThat(v.status).isEqualTo("REJECTED_QUALITY");
        CheckResult uniqueness = byName(v, "uniqueness");
        assertThat(uniqueness.passed()).isFalse();
        assertThat(uniqueness.metrics().get("duplicateRows")).isEqualTo(2L);
        assertThat(uniqueness.reasons()).isNotEmpty();
    }

    @Test
    @DisplayName("取值范围失败：age=999 越界，range 检查按列给出越界数与阈值")
    void rangeFailure() {
        String jsonl = row(1, 999, "2026-10-06T09:30:00Z") + "\n"
                + row(2, 50, "2026-10-06T09:30:00Z") + "\n";
        QualityRules rules = new QualityRules("q1", false, null,
                List.of("id"), 0.0,
                List.of(new RangeRule("age", 0.0, 120.0, 0.1)), 0.1, null);
        BatchVerdict v = submit(rules, jsonl);
        assertThat(v.status).isEqualTo("REJECTED_QUALITY");
        CheckResult range = byName(v, "range");
        assertThat(range.passed()).isFalse();
        assertThat(range.reasons()).hasSize(1);
    }

    @Test
    @DisplayName("时效性失败：最新数据早于 now-maxAge，freshness 不通过并给出 cutoff")
    void freshnessFailure() {
        String jsonl = row(1, 30, "2026-10-06T05:00:00Z") + "\n";
        BatchVerdict v = submit(standardRules(), jsonl);
        assertThat(v.status).isEqualTo("REJECTED_QUALITY");
        CheckResult freshness = byName(v, "freshness");
        assertThat(freshness.passed()).isFalse();
        assertThat(freshness.metrics().get("cutoff")).isEqualTo("2026-10-06T08:00:00Z");
        assertThat(freshness.reasons()).isNotEmpty();
    }

    @Test
    @DisplayName("检查可独立启停：关闭完整性/唯一性/范围，仅时效性生效")
    void checkToggles() {
        QualityRules onlyFreshness = new QualityRules("q1", false, null,
                null, null, null, null,
                new FreshnessRule("event_time", "PT2H", 0.0));
        String jsonl = row(1, 30, "2026-10-06T09:30:00Z") + "\n";
        BatchVerdict v = submit(onlyFreshness, jsonl);
        assertThat(v.status).isEqualTo("ACCEPTED");
        assertThat(byName(v, "completeness").enabled()).isFalse();
        assertThat(byName(v, "uniqueness").enabled()).isFalse();
        assertThat(byName(v, "range").enabled()).isFalse();
        assertThat(byName(v, "freshness").enabled()).isTrue();
    }

    private CheckResult byName(BatchVerdict v, String name) {
        return v.checks.stream().filter(c -> c.check().equals(name)).findFirst().orElseThrow();
    }
}
