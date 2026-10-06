package com.github.lechandonga.dataqualitydriftmonitorjava.tests;

import com.github.lechandonga.dataqualitydriftmonitorjava.model.Baseline;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.BatchVerdict;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.ColumnProfile;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.DriftReport;
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

import static org.assertj.core.api.Assertions.assertThat;

@BusinessCoverage("漂移监控边界：样本不足不可判定、数值/类别列漂移阈值边界、基线更新策略可控、并发不丢更新")
class DriftBoundaryTest extends AbstractApiTest {

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

    private String numericRow(long id, int age, String level) {
        return "{\"id\":" + id + ",\"name\":\"user\",\"age\":" + age
                + ",\"level\":\"" + level + "\",\"event_time\":\"2026-10-06T09:30:00Z\"}";
    }

    private SubmitRequest req(String content) {
        return new SubmitRequest("orders", "jsonl", null, null, content,
                null, null, null);
    }

    /** 生成一批：age 全部等于 age，level 全部等于 level，id 唯一 */
    private String batch(int fromId, int size, int age, String level) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(numericRow(fromId + i, age, level));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("基线样本不足：前两批（minBaselineSamples=2）漂移结论为 UNDECIDABLE")
    void undecidableWhenBaselineInsufficient() {
        BatchVerdict first = service.submit(req(batch(1, 20, 30, "A"))).verdict();
        assertThat(first.drift.decision).isEqualTo("UNDECIDABLE");
        assertThat(first.drift.reasons).isNotEmpty();

        BatchVerdict second = service.submit(req(batch(101, 20, 31, "A"))).verdict();
        assertThat(second.drift.decision).isEqualTo("UNDECIDABLE");
        assertThat(second.drift.baselineSampleBatches).isEqualTo(1);

        BatchVerdict third = service.submit(req(batch(201, 20, 30, "A"))).verdict();
        assertThat(third.drift.decision).isNotEqualTo("UNDECIDABLE");
        assertThat(third.drift.baselineSampleBatches).isEqualTo(2);
    }

    @Test
    @DisplayName("数值列漂移：均值稳定 NO_DRIFT；基线方差极小且均值偏移时 z-score 超阈值 → DRIFT")
    void numericDriftBoundary() {
        service.submit(req(batch(1, 30, 30, "A"))).verdict();
        service.submit(req(batch(101, 30, 30, "A"))).verdict();
        BatchVerdict normal = service.submit(req(batch(201, 30, 30, "A"))).verdict();
        assertThat(normal.drift.decision).isEqualTo("NO_DRIFT");

        BatchVerdict drifted = service.submit(req(batch(301, 30, 90, "A"))).verdict();
        assertThat(drifted.drift.decision).isEqualTo("DRIFT");
        DriftReport.ColumnDrift age = drifted.drift.columns.stream()
                .filter(c -> c.column().equals("age")).findFirst().orElseThrow();
        assertThat(age.decision()).isEqualTo("DRIFT");
        assertThat(age.metricName()).isEqualTo("meanZ");
        assertThat(age.metric()).isGreaterThan(age.threshold());
        assertThat(drifted.drift.reasons).anyMatch(r -> r.contains("age"));
    }

    @Test
    @DisplayName("类别列漂移：分布完全反转时 TVD=1，超过阈值 → DRIFT")
    void categoricalDrift() {
        service.submit(req(batch(1, 40, 30, "A"))).verdict();
        service.submit(req(batch(201, 40, 30, "A"))).verdict();
        BatchVerdict reversed = service.submit(req(batch(401, 40, 30, "B"))).verdict();
        assertThat(reversed.drift.decision).isEqualTo("DRIFT");
        DriftReport.ColumnDrift level = reversed.drift.columns.stream()
                .filter(c -> c.column().equals("level")).findFirst().orElseThrow();
        assertThat(level.decision()).isEqualTo("DRIFT");
        assertThat(level.metricName()).isEqualTo("tvd");
        assertThat(level.metric()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("MANUAL 策略：提交不更新基线；rebuild 后基线纳入通过批次")
    void manualBaselineStrategy() {
        Baseline cfg = new Baseline();
        cfg.updateStrategy = "MANUAL";
        adminService.updateBaselineConfig("orders", cfg);

        service.submit(req(batch(1, 20, 30, "A"))).verdict();
        service.submit(req(batch(101, 20, 30, "A"))).verdict();
        assertThat(store.getBaseline("orders").batchIds).isEmpty();

        BatchVerdict third = service.submit(req(batch(201, 20, 90, "A"))).verdict();
        assertThat(third.drift.decision).isEqualTo("UNDECIDABLE");

        adminService.rebuildBaseline("orders");
        assertThat(store.getBaseline("orders").batchIds).hasSize(3);
        BatchVerdict after = service.submit(req(batch(301, 20, 30, "A"))).verdict();
        assertThat(after.drift.decision).isEqualTo("NO_DRIFT");
    }

    @Test
    @DisplayName("ROLLING_WINDOW 策略：基线仅保留最近 windowSize 个通过批次，旧批次被剔除")
    void rollingWindowStrategy() {
        Baseline cfg = new Baseline();
        cfg.updateStrategy = "ROLLING_WINDOW";
        cfg.windowSize = 2;
        cfg.minBaselineSamples = 1;
        adminService.updateBaselineConfig("orders", cfg);

        service.submit(req(batch(1, 20, 30, "A"))).verdict();
        service.submit(req(batch(101, 20, 30, "A"))).verdict();
        service.submit(req(batch(201, 20, 30, "A"))).verdict();
        Baseline baseline = store.getBaseline("orders");
        assertThat(baseline.batchIds).hasSize(2);
        long rows = baseline.profile.get("id").totalCount;
        assertThat(rows).isEqualTo(40);
    }

    @Test
    @DisplayName("质量不通过批次不进基线：REJECTED_QUALITY 不更新样本数")
    void rejectedBatchExcludedFromBaseline() {
        BatchVerdict acceptedFirst = service.submit(req(batch(1, 20, 30, "A"))).verdict();
        BatchVerdict rejected = service.submit(req(batch(101, 20, 999, "A"))).verdict();
        BatchVerdict third = service.submit(req(batch(201, 20, 30, "A"))).verdict();
        assertThat(rejected.status).isEqualTo("REJECTED_QUALITY");
        assertThat(third.drift.decision).isEqualTo("UNDECIDABLE");
        assertThat(store.getBaseline("orders").batchIds)
                .containsExactlyInAnyOrder(acceptedFirst.batchId, third.batchId)
                .doesNotContain(rejected.batchId);
    }

    @Test
    @DisplayName("并发提交下画像合并无丢失：基线总行数严格等于并发批次数之和")
    void concurrentProfilesNoLoss() throws Exception {
        int batches = 20;
        int size = 10;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(10);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(batches);

        for (int b = 0; b < batches; b++) {
            int fromId = b * 1000 + 1;
            pool.submit(() -> {
                try {
                    start.await();
                    service.submit(req(batch(fromId, size, 30, "A"))).verdict();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        ColumnProfile ageProfile = store.getBaseline("orders").profile.get("age");
        assertThat(ageProfile.totalCount).isEqualTo((long) batches * size);
        assertThat(store.getBaseline("orders").batchIds).hasSize(batches);
    }
}
