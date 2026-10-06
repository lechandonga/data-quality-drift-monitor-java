package com.github.lechandonga.dataqualitydriftmonitorjava.tests;

import com.github.lechandonga.dataqualitydriftmonitorjava.drift.BaselineStrategy;
import com.github.lechandonga.dataqualitydriftmonitorjava.drift.DriftEvaluator;
import com.github.lechandonga.dataqualitydriftmonitorjava.drift.DriftResult;
import com.github.lechandonga.dataqualitydriftmonitorjava.drift.DriftStatus;
import com.github.lechandonga.dataqualitydriftmonitorjava.ingest.BatchParser;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchReport;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchService;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.ProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务覆盖：漂移监控 —— 样本不足不可判定、基线更新策略可控、
 * 并发多批提交时统计不丢失不覆盖。
 */
@SpringBootTest
class DriftMonitorTests {

    private static final Logger log = LoggerFactory.getLogger(DriftMonitorTests.class);

    @Autowired
    BatchService batchService;

    @Autowired
    com.github.lechandonga.dataqualitydriftmonitorjava.service.RuleService ruleService;

    @Autowired
    ProfileService profileService;

    @BeforeEach
    void resetProfiles() {
        DefaultRules.restore(ruleService);
        profileService.reset();
        profileService.configure(BaselineStrategy.ON_PASS, new DriftEvaluator(20, 3.0, 0.25));
    }

    @Test
    void insufficientBaselineSamplesAreIndeterminate() {
        log.info("[漂移监控] 用例：基线样本不足 minSamples=20，期望 INDETERMINATE 而非确定结论");
        BatchReport report = batchService.submit("drift-small", TestData.csv(5, 100.0),
                BatchParser.Format.CSV);
        assertThat(report.driftResults()).isNotEmpty();
        assertThat(report.driftResults()).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(DriftStatus.INDETERMINATE);
            assertThat(r.detail()).contains("minSamples");
        });
        log.info("[漂移监控] 通过：{}", report.driftResults());
    }

    @Test
    void baselineStrategyControlsUpdates() {
        log.info("[漂移监控] 用例：baselineStrategy=NONE 时基线不更新，ON_PASS 时仅吸收 PASS 批次");
        profileService.configure(BaselineStrategy.NONE, new DriftEvaluator(20, 3.0, 0.25));
        batchService.submit("drift-none-1", TestData.csv(30, 100.0), BatchParser.Format.CSV);
        assertThat(profileService.baselineSamples("amount")).isZero();

        profileService.configure(BaselineStrategy.ON_PASS, new DriftEvaluator(20, 3.0, 0.25));
        batchService.submit("drift-onpass-1", TestData.csv(30, 100.0), BatchParser.Format.CSV);
        assertThat(profileService.baselineSamples("amount")).isEqualTo(30);
        log.info("[漂移监控] 通过：NONE 不更新，ON_PASS 吸收后 baselineSamples=30");
    }

    @Test
    void shiftedBatchIsFlaggedAsDrift() {
        log.info("[漂移监控] 用例：建立基线后提交均值大幅偏移的批次，期望 DRIFT");
        batchService.submit("drift-base", TestData.csv(30, 100.0), BatchParser.Format.CSV);
        BatchReport shifted = batchService.submit("drift-shift", TestData.csv(30, 100000.0),
                BatchParser.Format.CSV);
        DriftResult amount = shifted.driftResults().stream()
                .filter(r -> r.column().equals("amount")).findFirst().orElseThrow();
        assertThat(amount.status()).isEqualTo(DriftStatus.DRIFT);
        log.info("[漂移监控] 通过：{}", amount.detail());
    }

    @Test
    void concurrentBatchesDoNotLoseStatistics() throws Exception {
        log.info("[漂移监控] 用例：并发提交多批不同数据，基线统计须完整无丢失");
        int batches = 8;
        int rowsPerBatch = 5;
        ExecutorService pool = Executors.newFixedThreadPool(batches);
        try {
            List<Callable<BatchReport>> tasks = new ArrayList<>();
            for (int i = 0; i < batches; i++) {
                String batchId = "drift-conc-" + i;
                byte[] payload = TestData.csv(rowsPerBatch, 100.0 + i);
                tasks.add(() -> batchService.submit(batchId, payload, BatchParser.Format.CSV));
            }
            List<Future<BatchReport>> futures = pool.invokeAll(tasks);
            for (Future<BatchReport> future : futures) {
                assertThat(future.get().verdict()).isNotNull();
            }
            assertThat(profileService.baselineSamples("amount"))
                    .isEqualTo((long) batches * rowsPerBatch);
            log.info("[漂移监控] 通过：{} 批并发提交后 baselineSamples={}（期望 {}）",
                    batches, profileService.baselineSamples("amount"), batches * rowsPerBatch);
        } finally {
            pool.shutdown();
        }
    }
}
