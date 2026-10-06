package com.github.lechandonga.dataqualitydriftmonitorjava.tests;

import com.github.lechandonga.dataqualitydriftmonitorjava.model.BatchVerdict;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.QualityRules;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.RangeRule;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.AdminService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@BusinessCoverage("规则版本与历史复现：规则/阈值调整后历史批次结论与所用规则快照保持不变，旧版本仍可指定")
class RuleVersioningTest extends AbstractApiTest {

    @Autowired
    private Store store;
    @Autowired
    private AdminService adminService;

    private BatchService service;
    private final Clock fixed = Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        store.resetForTesting();
        service = new BatchService(store, objectMapper, fixed);
        adminService.registerContract(standardContract("orders"));
        adminService.registerRules("orders", standardRules());
    }

    private String batchWithAge(int age) {
        return "{\"id\":1,\"name\":\"n1\",\"age\":" + age
                + ",\"level\":\"A\",\"event_time\":\"2026-10-06T09:30:00Z\"}";
    }

    private SubmitRequest requestWithRulesVersion(String content, String rulesVersion) {
        return new SubmitRequest("orders", "jsonl", null, null, content,
                rulesVersion, null, null);
    }

    @Test
    @DisplayName("阈值收紧（age 上限 120→40）不改变历史批次结论，快照仍是旧规则")
    void ruleTighteningKeepsHistory() {
        BatchVerdict historical = service.submit(requestWithRulesVersion(batchWithAge(100), null)).verdict();
        assertThat(historical.status).isEqualTo("ACCEPTED");
        String historicalBatchId = historical.batchId;

        QualityRules v2 = new QualityRules("v2", true, 0.0,
                List.of("id"), 0.0,
                List.of(new RangeRule("age", 0.0, 40.0, 0.0)), 0.0,
                standardRules().freshness());
        adminService.registerRules("orders", v2);

        BatchVerdict newBatch = service.submit(requestWithRulesVersion(
                batchWithAge(100).replace("\"id\":1", "\"id\":2"), null)).verdict();
        assertThat(newBatch.status).isEqualTo("REJECTED_QUALITY");
        assertThat(newBatch.rulesVersion).isEqualTo("v2");

        BatchVerdict reloaded = store.findVerdict("orders", historicalBatchId);
        assertThat(reloaded.status).isEqualTo("ACCEPTED");
        assertThat(reloaded.rulesVersion).isEqualTo("v1");
        assertThat(reloaded.rulesSnapshot.version()).isEqualTo("v1");
        assertThat(reloaded.rulesSnapshot.rangeRules().get(0).max()).isEqualTo(120.0);
        assertThat(reloaded.checks).hasSize(4);
    }

    @Test
    @DisplayName("规则放宽后，可显式指定旧版本复现当时判定；已存在版本号不可覆盖")
    void pinOldVersionAndNoOverwrite() {
        QualityRules v2 = new QualityRules("v2", true, 0.5,
                List.of("id"), 0.5, List.of(), 1.0, standardRules().freshness());
        adminService.registerRules("orders", v2);

        BatchVerdict byOld = service.submit(requestWithRulesVersion(batchWithAge(999), "v1")).verdict();
        assertThat(byOld.rulesVersion).isEqualTo("v1");
        assertThat(byOld.status).isEqualTo("REJECTED_QUALITY");

        BatchVerdict byNew = service.submit(requestWithRulesVersion(
                batchWithAge(999).replace("\"id\":1", "\"id\":2"), "v2")).verdict();
        assertThat(byNew.rulesVersion).isEqualTo("v2");
        assertThat(byNew.status).isEqualTo("ACCEPTED");

        assertThat(adminService.listRuleVersions("orders").keySet()).containsExactly("v1", "v2");
        assertThatThrownBy(() -> adminService.registerRules("orders",
                new QualityRules("v1", false, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不可覆盖");
    }
}
