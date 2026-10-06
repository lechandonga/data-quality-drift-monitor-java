package com.github.lechandonga.dataqualitydriftmonitorjava.tests;

import com.github.lechandonga.dataqualitydriftmonitorjava.ingest.BatchParser;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchReport;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchService;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchStore;
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
 * 业务覆盖：幂等 —— 同一 batchId 重复提交（含并发重复提交）结论一致且不重复计数。
 */
@SpringBootTest
class IdempotencyTests {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyTests.class);

    @Autowired
    BatchService batchService;

    @Autowired
    com.github.lechandonga.dataqualitydriftmonitorjava.service.RuleService ruleService;

    @Autowired
    BatchStore batchStore;

    @Autowired
    ProfileService profileService;

    @BeforeEach
    void restoreDefaults() {
        DefaultRules.restore(ruleService);
    }

    @Test
    void sequentialDuplicateReturnsSameConclusionAndDoesNotRecount() {
        log.info("[幂等] 用例：同一 batchId 顺序重复提交，结论一致且画像不重复累计");
        profileService.reset();
        byte[] payload = TestData.csv(10, 100.0);
        BatchReport first = batchService.submit("idem-seq", payload, BatchParser.Format.CSV);
        long samplesAfterFirst = profileService.baselineSamples("amount");
        BatchReport second = batchService.submit("idem-seq", payload, BatchParser.Format.CSV);
        assertThat(second.duplicate()).isTrue();
        assertThat(second.verdict()).isEqualTo(first.verdict());
        assertThat(second.ruleVersion()).isEqualTo(first.ruleVersion());
        assertThat(profileService.baselineSamples("amount")).isEqualTo(samplesAfterFirst);
        assertThat(batchStore.find("idem-seq")).isPresent();
        assertThat(batchStore.find("idem-seq").orElseThrow().duplicate()).isFalse();
        log.info("[幂等] 通过：verdict={} baselineSamples={}（未重复计数）",
                second.verdict(), samplesAfterFirst);
    }

    @Test
    void concurrentDuplicatesProduceSingleConsistentReport() throws Exception {
        log.info("[幂等] 用例：同一 batchId 并发重复提交，仅处理一次且结论一致");
        profileService.reset();
        byte[] payload = TestData.csv(10, 100.0);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<BatchReport>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> batchService.submit("idem-concurrent", payload, BatchParser.Format.CSV));
            }
            List<Future<BatchReport>> futures = pool.invokeAll(tasks);
            List<BatchReport> reports = new ArrayList<>();
            for (Future<BatchReport> future : futures) {
                reports.add(future.get());
            }
            assertThat(reports).allMatch(r -> r.verdict() == reports.get(0).verdict());
            assertThat(reports).allMatch(r -> r.ruleVersion().equals(reports.get(0).ruleVersion()));
            assertThat(reports.stream().filter(r -> !r.duplicate()).count()).isEqualTo(1);
            assertThat(profileService.baselineSamples("amount")).isEqualTo(10);
            log.info("[幂等] 通过：{} 个并发提交仅 1 次真实处理，baselineSamples={}",
                    threads, profileService.baselineSamples("amount"));
        } finally {
            pool.shutdown();
        }
    }
}
